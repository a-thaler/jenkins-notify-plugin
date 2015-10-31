package org.jenkinsci.plugins.notify;

import static org.apache.http.util.Args.notBlank;
import static org.apache.http.util.Args.notNull;

import com.google.common.io.Resources;

import groovy.json.JsonSlurper;
import groovy.text.SimpleTemplateEngine;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import hudson.util.ReflectionUtils;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import jenkins.model.Jenkins;

import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.Asserts;
import org.apache.http.util.EntityUtils;
import org.apache.http.util.TextUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.management.RuntimeErrorException;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class NotifyRecorder extends Recorder
{
    private static final int        CONNECT_TIMEOUT         = 10000;
    private static final int        CONNECT_REQUEST_TIMEOUT = 10000;
    private static final int        SOCKET_TIMEOUT          = 10000;
    private static final String     JSON_FUNCTION           = loadResource( "/json.groovy" );
    private static final String     VICTOR_TEMPLATE        = loadResource( "/victor-template.json"  );
    private static final String     STATUS_TEMPLATE        = loadResource( "/status-template.json"  );
    private static final String     LINE                    = "\n---------------\n";

    @Nullable public  final     String        notifyUrlVictor;
    @Nullable public  final     String        notifyOnVictor;
    @Nonnull  public  final     String        notifyTemplateVictor;
    @Nullable public  final     String        notifyUrlStatus;
    @Nullable public  final     String        notifyOnStatus;
    @Nonnull  public  final     String        notifyTemplateStatus;
    @Nonnull  public  final     String        notifyAuthorizationStatus;
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


    private static String loadResource ( String resourcePath ) {
        try
        {
            URL resourceUrl = notNull( NotifyRecorder.class.getResource( notBlank( resourcePath, "Resource path" )), "Resource URL" );
            return notBlank( Resources.toString( resourceUrl, StandardCharsets.UTF_8 ),
                             String.format( "Resource '%s'", resourcePath ));
        }
        catch ( Exception e )
        {
            throw new RuntimeException( String.format( "Failed to load resource '%s': %s", resourcePath, e ),
                                        e );
        }
    }


    @SuppressWarnings({ "ParameterHidesMemberVariable", "SuppressionAnnotation" })
    @DataBoundConstructor
    public NotifyRecorder ( String notifyUrlVictor, String notifyOnVictor, String notifyTemplateVictor,
    	String notifyUrlStatus, String notifyOnStatus, String notifyTemplateStatus, String notifyAuthorizationStatus ) {
        this.notifyUrlVictor      = TextUtils.isBlank( notifyUrlVictor ) ? null : notifyUrlVictor.trim();
        this.notifyOnVictor       = TextUtils.isBlank( notifyOnVictor ) ? Result.SUCCESS.toString() : notifyOnVictor.trim();
        this.notifyTemplateVictor = ( TextUtils.isBlank( notifyTemplateVictor ) ? VICTOR_TEMPLATE : notifyTemplateVictor ).trim();
        this.notifyUrlStatus      = TextUtils.isBlank( notifyUrlStatus ) ? null : notifyUrlStatus.trim();
        this.notifyOnStatus       = TextUtils.isBlank( notifyOnStatus ) ? Result.SUCCESS.toString() : notifyOnStatus.trim();
        this.notifyTemplateStatus = ( TextUtils.isBlank( notifyTemplateStatus ) ? VICTOR_TEMPLATE : notifyTemplateStatus ).trim();
        this.notifyAuthorizationStatus = ( TextUtils.isBlank( notifyAuthorizationStatus ) ? null : notifyAuthorizationStatus ).trim();
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
    public boolean perform ( AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener )
        throws InterruptedException, IOException
    {
        this.listener              = notNull( listener, "Build listener" );

        listener.getLogger().println();
        notifyVictor(build);
        listener.getLogger().println();
        notifyStatus(build);
        return true;
    }
    
    private void notifyVictor(AbstractBuild<?, ?> build) throws InterruptedException, IOException{
    	boolean isIntermediateStep = build.getUrl().contains( "$" );
        boolean isNotifyableResult    = build.getResult().isBetterOrEqualTo( Result.fromString(notifyOnVictor) );
    	if ( isIntermediateStep || TextUtils.isBlank ( notifyUrlVictor ) ) {
            listener.getLogger().println( String.format( "Skipping VictorOps notification as configured url is empty"));
            return;
        }
        if ( ! isNotifyableResult ) {
            listener.getLogger().println( String.format( "Skipping VictorOps notification as build result level is configured to %s", notifyOnVictor));
            return;
        }

        String jsonPayload = transformPayload( build, build.getEnvironment( listener ), notifyTemplateVictor);
        validateJson(jsonPayload);

        listener.getLogger().println( String.format( "Notifying VictorOps as build result level %s is matching notification level %s", build.getResult(), notifyOnVictor ));
        listener.getLogger().println( String.format( "Using URL %s", notifyUrlVictor ));
        listener.getLogger().println( String.format( "Using payload %s", jsonPayload ));
        
        HttpPost request = new HttpPost();
        request.setURI(URI.create(notBlank(notifyUrlVictor, "Notify URL")));
		request.setEntity(new StringEntity(notBlank(jsonPayload, "Notify JSON"),
				ContentType.create("application/json", Consts.UTF_8)));
		
        // noinspection ConstantConditions
        sendNotifyRequestWithRetry(request);
        
        listener.getLogger().println( String.format( "Notification of victorOps was SUCCESSFUL" ));
    }
    
    private void notifyStatus(AbstractBuild<?, ?> build) throws InterruptedException, IOException{
    	boolean isIntermediateStep = build.getUrl().contains( "$" );
        boolean isNotifyableResult    = build.getResult().isBetterOrEqualTo( Result.fromString(notifyOnStatus) );
    	if ( isIntermediateStep || TextUtils.isBlank ( notifyUrlStatus ) ) {
            listener.getLogger().println( String.format( "Skipping Statuspage notification as configured url is empty"));
            return;
        }
        if ( ! isNotifyableResult ) {
            listener.getLogger().println( String.format( "Skipping Statuspage notification as build result level is configured to %s", notifyOnStatus));
            return;
        }

        String notifyPayload = transformPayload( build, build.getEnvironment( listener ), notifyTemplateStatus);

        String[] formData=notifyPayload.split("=");
        if(formData.length!=2){
        	throw new RuntimeException( String.format( "Payload is not a valid form parameter in syntax 'a=b', was %s", notifyPayload));
        }
        
        listener.getLogger().println( String.format( "Notifying Statuspage as build result level %s is matching notification level %s", build.getResult(), notifyOnStatus ));
        listener.getLogger().println( String.format( "Using URL %s", notifyUrlStatus ));
        listener.getLogger().println( String.format( "Using payload %s", notifyPayload ));
        
        HttpPatch request=new HttpPatch();
        if(notifyAuthorizationStatus!=null&&!notifyAuthorizationStatus.isEmpty())
        {
        	listener.getLogger().println("Using Authorization header");
        	request.setHeader("Authorization", notifyAuthorizationStatus);
        }
		request.setURI(URI.create(notBlank(notifyUrlStatus, "Notify URL")));
		UrlEncodedFormEntity entity = new UrlEncodedFormEntity(Arrays.asList(new BasicNameValuePair(formData[0],formData[1])));
		request.setEntity(entity);
		
        // noinspection ConstantConditions
        sendNotifyRequestWithRetry(request);
        
        listener.getLogger().println( String.format( "Notification of statuspage was SUCCESSFUL" ));
    }

    /**
     * Determines the possible failure reasons of given build based on results of "Build Failure Analyzer" plugin.
     * Returned collection will contain instances of com.sonyericsson.jenkins.plugins.bfa.FoundFailureCause .
     * @param build the build to get the failure reasons from
     * @return set of failure reasons
     */
    private Collection<Object> buildPossibleFailureCauses(final AbstractBuild build)
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
    
    private String transformPayload( @Nonnull final AbstractBuild build,
                                    @Nonnull final Map<String,?> env, String rawTemplate)
    {
        Map<String,?> binding = new HashMap<String, Object>(){{
           put( "jenkins", notNull( Jenkins.getInstance(), "Jenkins instance" ));
           put( "build",   notNull( build, "Build instance" ));
           put( "env",     notNull( env, "Build environment" ));
           put( "reasons", notNull( buildPossibleFailureCauses(build), "Possible Failure Causes collected by 'Build Failure Analyzer' plugin" ));
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
    
    private void validateJson(String json){
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

    private void sendNotifyRequestWithRetry(HttpEntityEnclosingRequestBase request)
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
						"%s. try: Failed to publish notify request", i));
			}
		}
		while(lastException!=null && i<5);
		
		if(lastException!=null){
			throw new RuntimeException(
					String.format(
							"Retries exhausted, failed to publish notify request"), lastException);
		}
	}

	private void sendNotifyRequest(HttpEntityEnclosingRequestBase request)
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

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher>
    {
    	private String notifyOn;
    	
    	public DescriptorImpl() {
			load();
		}
        @Override
        public String getDisplayName ()
        {
            return "Publish Notifications to VictorOps and StatusPage";
        }

        @Override
        public boolean isApplicable ( Class<? extends AbstractProject> jobType )
        {
            return true;
        }

        public String getVictorNotifyTemplate()
        {
            return VICTOR_TEMPLATE;
        }
        
        public String getStatusNotifyTemplate()
        {
            return STATUS_TEMPLATE;
        }

        public FormValidation doCheckNotifyUrlVictor( @QueryParameter String notifyUrlVictor ) {
        	return doCheckNotifyUrlInternal(notifyUrlVictor);
        }
        
        public FormValidation doCheckNotifyUrlStatus( @QueryParameter String notifyUrlStatus ) {
        	return doCheckNotifyUrlInternal(notifyUrlStatus);
        }
        
        private FormValidation doCheckNotifyUrlInternal( String notifyUrl ) {

            if ( TextUtils.isBlank( notifyUrl )) {
                return FormValidation.ok();
            }

            try {
                URI urlObject = new URI(notifyUrl);
                String scheme = urlObject.getScheme();
                String host   = urlObject.getHost();

                if ( ! (( "http".equals( scheme )) || ( "https".equals( scheme )))) {
                    return FormValidation.error( "URL should start with 'http://' or 'https://'" );
                }

                if ( TextUtils.isBlank( host )) {
                    return FormValidation.error( "URL should contain a host" );
                }

            } catch ( Exception e ) {
                return FormValidation.error( e, "Invalid URL provided" );
            }

            return FormValidation.ok();
        }


        public FormValidation doCheckNotifyTemplateVictor( @QueryParameter String notifyTemplateVictor ) {
            return FormValidation.ok();
        }
        
        public FormValidation doCheckNotifyOnVictor( @QueryParameter String notifyOnVictor ) {
            return FormValidation.ok();
        }
        
        public FormValidation doCheckNotifyTemplateStatus( @QueryParameter String notifyTemplateStatus ) {
            return FormValidation.ok();
        }
        
        public FormValidation doCheckNotifyOnStatus( @QueryParameter String notifyOnStatus ) {
            return FormValidation.ok();
        }
        
        public FormValidation doCheckNotifyAuthorizationStatus( @QueryParameter String notifyAuthorizationStatus ) {
            return FormValidation.ok();
        }
        
        public ListBoxModel doFillNotifyOnVictorItems() {
            return doFillNotifyOnInternalItems();
        }
        
        public ListBoxModel doFillNotifyOnStatusItems() {
            return doFillNotifyOnInternalItems();
        }
        
        private ListBoxModel doFillNotifyOnInternalItems() {
            ListBoxModel items = new ListBoxModel();
            items.add(createOption(Result.SUCCESS));
            items.add(createOption(Result.UNSTABLE));
            items.add(createOption(Result.FAILURE));
            items.add(createOption(Result.NOT_BUILT));
            items.add(createOption(Result.ABORTED));
            return items;
        }
        
        private Option createOption(Result result){
            return new Option(result.toString(), result.toString(), notifyOn!=null && notifyOn.equals(result.toString()));
        }
    }
}
