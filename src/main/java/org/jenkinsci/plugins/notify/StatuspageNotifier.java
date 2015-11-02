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


public class StatuspageNotifier extends AbstractNotifier
{
    private static final String     STATUS_TEMPLATE        = loadResource( "/status-template.json"  );

    @Nullable public  final     String        notifyUrl;
    @Nullable public  final     String        notifyOn;
    @Nonnull  public  final     String        notifyTemplate;
    @Nonnull  public  final     String        notifyAuthorization;

    @SuppressWarnings({ "ParameterHidesMemberVariable", "SuppressionAnnotation" })
    @DataBoundConstructor
    public StatuspageNotifier ( String notifyUrl, String notifyOn, String notifyTemplate, String notifyAuthorization ) {
        this.notifyUrl      = TextUtils.isBlank( notifyUrl ) ? null : notifyUrl.trim();
        this.notifyOn       = TextUtils.isBlank( notifyOn ) ? Result.SUCCESS.toString() : notifyOn.trim();
        this.notifyTemplate = ( TextUtils.isBlank( notifyTemplate ) ? STATUS_TEMPLATE : notifyTemplate ).trim();
        this.notifyAuthorization = ( TextUtils.isBlank( notifyAuthorization ) ? null : notifyAuthorization ).trim();
    }

    /**
     * Publishes JSON payload to notify URL.
     */
    @SuppressWarnings({ "MethodWithMultipleReturnPoints", "SuppressionAnnotation" })
    @Override
    public void notify ( AbstractBuild<?, ?> build, BuildListener listener )
        throws InterruptedException, IOException
    {
    	boolean isIntermediateStep = build.getUrl().contains( "$" );
        boolean isNotifyableResult    = build.getResult().isBetterOrEqualTo( Result.fromString(notifyOn) );
    	if ( isIntermediateStep || TextUtils.isBlank ( notifyUrl ) ) {
            listener.getLogger().println( String.format( "Skipping Statuspage notification as configured url is empty"));
            return;
        }
        if ( ! isNotifyableResult ) {
            listener.getLogger().println( String.format( "Skipping Statuspage notification as build result level is configured to %s", notifyOn));
            return;
        }

        String notifyPayload = transformPayload( build, build.getEnvironment( listener ), notifyTemplate);

        String[] formData=notifyPayload.split("=");
        if(formData.length!=2){
        	throw new RuntimeException( String.format( "Payload is not a valid form parameter in syntax 'a=b', was %s", notifyPayload));
        }
        
        listener.getLogger().println( String.format( "Notifying Statuspage as build result level %s is matching notification level %s", build.getResult(), notifyOn ));
        listener.getLogger().println( String.format( "Using URL %s", notifyUrl ));
        listener.getLogger().println( String.format( "Using payload %s", notifyPayload ));
        
        HttpPatch request=new HttpPatch();
        if(notifyAuthorization!=null&&!notifyAuthorization.isEmpty())
        {
        	listener.getLogger().println("Using Authorization header");
        	request.setHeader("Authorization", notifyAuthorization);
        }
		request.setURI(URI.create(notBlank(notifyUrl, "Notify URL")));
		UrlEncodedFormEntity entity = new UrlEncodedFormEntity(Arrays.asList(new BasicNameValuePair(formData[0],formData[1])));
		request.setEntity(entity);
		
        // noinspection ConstantConditions
        sendNotifyRequestWithRetry(request);
        
        listener.getLogger().println( String.format( "Notification of statuspage was SUCCESSFUL" ));
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
            return "Publish Notification to StatusPage";
        }

        @Override
        public boolean isApplicable ( Class<? extends AbstractProject> jobType )
        {
            return true;
        }

        public String getNotifyTemplate()
        {
            return STATUS_TEMPLATE;
        }

        public FormValidation doCheckNotifyUrl( @QueryParameter String notifyUrl ) {
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

        public FormValidation doCheckNotifyTemplate( @QueryParameter String notifyTemplate ) {
            return FormValidation.ok();
        }
        
        public FormValidation doCheckNotifyOn( @QueryParameter String notifyOn ) {
            return FormValidation.ok();
        }
        
        public FormValidation doCheckNotifyAuthorization( @QueryParameter String notifyAuthorization ) {
            return FormValidation.ok();
        }
        
        public ListBoxModel doFillNotifyOnItems() {
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
