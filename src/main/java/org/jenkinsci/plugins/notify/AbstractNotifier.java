package org.jenkinsci.plugins.notify;

import static org.apache.http.util.Args.notBlank;
import static org.apache.http.util.Args.notNull;
import groovy.json.JsonSlurper;
import groovy.text.SimpleTemplateEngine;
import hudson.Launcher;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;
import hudson.util.ReflectionUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import jenkins.model.Jenkins;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.Asserts;
import org.apache.http.util.EntityUtils;

import com.google.common.io.Resources;

public abstract class AbstractNotifier extends Recorder{
	private static final int        CONNECT_TIMEOUT         = 10000;
    private static final int        CONNECT_REQUEST_TIMEOUT = 10000;
    private static final int        SOCKET_TIMEOUT          = 10000;
    private static final String     JSON_FUNCTION           = loadResource( "/json.groovy" );
    private static final String     LINE                    = "\n---------------\n";

    @Nonnull  private transient BuildListener listener;

    private CloseableHttpClient buildHttpClient () {
        RequestConfig requestConfig = RequestConfig.custom().
                                      setConnectTimeout( CONNECT_TIMEOUT ).
                                      setSocketTimeout( SOCKET_TIMEOUT ).
                                      setConnectionRequestTimeout( CONNECT_REQUEST_TIMEOUT ).
                                      build();

        return HttpClients.custom().setDefaultRequestConfig( requestConfig ).
               build();
    }

    protected static String loadResource ( String resourcePath ) {
        try
        {
            URL resourceUrl = notNull( AbstractNotifier.class.getResource( notBlank( resourcePath, "Resource path" )), "Resource URL" );
            return notBlank( Resources.toString( resourceUrl, StandardCharsets.UTF_8 ),
                             String.format( "Resource '%s'", resourcePath ));
        }
        catch ( Exception e )
        {
            throw new RuntimeException( String.format( "Failed to load resource '%s': %s", resourcePath, e ),
                                        e );
        }
    }

    public BuildStepMonitor getRequiredMonitorService ()
    {
        return BuildStepMonitor.NONE;
    }

    /**
     * Publishes JSON payload to notify URL.
     */
    @SuppressWarnings({ "MethodWithMultipleReturnPoints", "SuppressionAnnotation" })
    @Override
    public final boolean perform ( AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener )
        throws InterruptedException, IOException
    {
        this.listener              = notNull( listener, "Build listener" );
        listener.getLogger().println();
        notify(build, this.listener);
        return true;
    }
    
    protected abstract void notify ( AbstractBuild<?, ?> build, BuildListener listener )
            throws InterruptedException, IOException;

    /**
     * Determines the possible failure reasons of given build based on results of "Build Failure Analyzer" plugin.
     * Returned collection will contain instances of com.sonyericsson.jenkins.plugins.bfa.FoundFailureCause .
     * @param build the build to get the failure reasons from
     * @return set of failure reasons
     */
    protected Collection<Object> buildPossibleFailureCauses(final AbstractBuild build)
    {
    	Map<String,Object> reasons=new LinkedHashMap<String,Object>();
    	if(Result.SUCCESS!=build.getResult()){
    		listener.getLogger().println( String.format( "Searching for build failure reasons" ));
	    	for(Action action:build.getAllActions()){
	    		try{
		    		if(action.getClass().getSimpleName().equals("FailureCauseBuildAction")){
		    			listener.getLogger().println( String.format( "Found build action '%s'", action.getDisplayName() ));
		    			Method  method=ReflectionUtils.findMethod(action.getClass(), "getFoundFailureCauses");
		    			Object failureCausesObject=method.invoke(action, new Object[]{});
		    			if(failureCausesObject instanceof List){
		    				List<Object> failureCauses=(List) failureCausesObject;
		    				for(Object failureCause:failureCauses){
		    					Method  nameMethod=ReflectionUtils.findMethod(failureCause.getClass(), "getName");
		    					Object nameObject=nameMethod.invoke(failureCause, new Object[]{});
		    					listener.getLogger().println( String.format( "Found failure cause '%s'", nameObject ));
		    					if(nameObject instanceof String){
		    						reasons.put((String) nameObject,failureCause);
		    					}
		    				}
		    			}
		    		}
	    		}catch(InvocationTargetException e){
	    			listener.getLogger().println( String.format( "Error while determing possible failure causes %s", e.getMessage() ));
	    		}catch(IllegalAccessException e){
	    			listener.getLogger().println( String.format( "Error while determing possible failure causes %s", e.getMessage() ));
	    		}
	    	}
    	}
    	return reasons.values();
    }
    
    /*
     * Assures that the push happens at the end, mainly after the build failure analyzer was running already
     */
    @Override
    public boolean needsToRunAfterFinalized() {
        return true;
    }
    
    protected String transformPayload( @Nonnull final AbstractBuild build,
                                    @Nonnull final Map<String,?> env, String rawTemplate)
    {
        Map<String,?> binding = new HashMap<String, Object>(){{
           put( "jenkins", notNull( Jenkins.getInstance(), "Jenkins instance" ));
           put( "build",   notNull( build, "Build instance" ));
           put( "env",     notNull( env, "Build environment" ));
           put( "reasons", notNull( buildPossibleFailureCauses(build), "Possible Failure Causes collected by 'Build Failure Analyzer' plugin" ));
           put( "helper",  notNull( new Helper(build), "Helper for build status"));
        }};

        String json     = null;
        String template = "<%\n\n" + JSON_FUNCTION + "\n\n%>\n\n" +
                          notBlank( rawTemplate, "Notify template" );

        try
        {
            json = notBlank( new SimpleTemplateEngine( getClass().getClassLoader()).
                             createTemplate( template ).
                             make( binding ).toString(), "Payload JSON" ).trim();
        }
        catch ( Exception e )
        {
            throw new RuntimeException((
                String.format( "Failed to parse Groovy template:%s%s%s",
                               LINE, template, LINE )));
        }
        return json;
    }
    
    protected void validateJson(String json){
        try
        {
        	Asserts.check(( json.startsWith( "{" ) && json.endsWith( "}" )) ||
                ( json.startsWith( "[" ) && json.endsWith( "]" )),"JSON payload does not start with { or [");

            Asserts.notNull( new JsonSlurper().parseText( json ), "Parsed JSON" );
	    }
	    catch ( Exception e )
	    {
	        throw new RuntimeException(
	            String.format( "Failed to validate JSON payload (check with http://jsonlint.com/):%s%s%s",
	                           LINE, json, LINE ), e );
	    }
    }

    protected void sendNotifyRequestWithRetry(HttpEntityEnclosingRequestBase request)
			throws IOException {
		Exception lastException=null;
		int i=0;
		do{
			try {
				sendNotifyRequest(request);
				lastException=null;
			} catch (Exception e) {
				lastException=e;
				i++;
				listener.getLogger().println(String.format(
						"%s. try: Failed to publish notify request: %s", i,e.getMessage()));
			}
		}
		while(lastException!=null && i<5);
		
		if(lastException!=null){
			throw new RuntimeException(
					String.format(
							"Retries exhausted, failed to publish notify request"), lastException);
		}
	}

	protected void sendNotifyRequest(HttpEntityEnclosingRequestBase request)
			throws IOException {
		CloseableHttpClient httpclient = buildHttpClient();
		try {
			CloseableHttpResponse response = httpclient.execute(request);
			try {
				int statusCode = response.getStatusLine().getStatusCode();
				Asserts.check(statusCode == 200, String.format(
						"status code is %s, expected 200", statusCode));
				EntityUtils.consume(notNull(response.getEntity(),
						"Response entity"));
			} finally {
				response.close();
			}
		} finally {
			httpclient.close();
		}
	}
}
