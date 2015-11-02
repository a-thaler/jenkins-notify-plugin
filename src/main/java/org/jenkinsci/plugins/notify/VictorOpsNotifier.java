package org.jenkinsci.plugins.notify;

import static org.apache.http.util.Args.notBlank;
import hudson.Extension;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;

import java.io.IOException;
import java.net.URI;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.http.Consts;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.TextUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;


public class VictorOpsNotifier extends AbstractNotifier
{
    private static final String     VICTOR_TEMPLATE        = loadResource( "/victor-template.json"  );

    @Nullable public  final     String        notifyUrl;
    @Nullable public  final     String        notifyOn;
    @Nonnull  public  final     String        notifyTemplate;

    @SuppressWarnings({ "ParameterHidesMemberVariable", "SuppressionAnnotation" })
    @DataBoundConstructor
    public VictorOpsNotifier ( String notifyUrl, String notifyOn, String notifyTemplate) {
        this.notifyUrl      = TextUtils.isBlank( notifyUrl ) ? null : notifyUrl.trim();
        this.notifyOn       = TextUtils.isBlank( notifyOn ) ? Result.SUCCESS.toString() : notifyOn.trim();
        this.notifyTemplate = ( TextUtils.isBlank( notifyTemplate ) ? VICTOR_TEMPLATE : notifyTemplate ).trim();
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
            listener.getLogger().println( String.format( "Skipping VictorOps notification as configured url is empty"));
            return;
        }
        if ( ! isNotifyableResult ) {
            listener.getLogger().println( String.format( "Skipping VictorOps notification as build result level is configured to %s", notifyOn));
            return;
        }

        String jsonPayload = transformPayload( build, build.getEnvironment( listener ), notifyTemplate);
        validateJson(jsonPayload);

        listener.getLogger().println( String.format( "Notifying VictorOps as build result level %s is matching notification level %s", build.getResult(), notifyOn ));
        listener.getLogger().println( String.format( "Using URL %s", notifyUrl ));
        listener.getLogger().println( String.format( "Using payload %s", jsonPayload ));
        
        HttpPost request = new HttpPost();
        request.setURI(URI.create(notBlank(notifyUrl, "Notify URL")));
		request.setEntity(new StringEntity(notBlank(jsonPayload, "Notify JSON"),
				ContentType.create("application/json", Consts.UTF_8)));
		
        // noinspection ConstantConditions
        sendNotifyRequestWithRetry(request);
        
        listener.getLogger().println( String.format( "Notification of victorOps was SUCCESSFUL" ));
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
            return "Publish Notification to VictorOps";
        }

        @Override
        public boolean isApplicable ( Class<? extends AbstractProject> jobType )
        {
            return true;
        }

        public String getNotifyTemplate()
        {
            return VICTOR_TEMPLATE;
        }
        
        public FormValidation doCheckNotifyUrl( @QueryParameter String notifyUrl) {
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
