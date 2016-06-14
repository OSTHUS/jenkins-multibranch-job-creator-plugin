package de.osthus.jenkins.plugins.git;

import java.io.IOException;

import javax.servlet.ServletException;

import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.GitURIRequirementsBuilder;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

@Extension
public class GitflowBuilderDescriptor extends BuildStepDescriptor<Builder>
{
    private static final String PLUGIN_TITLE = "Gitflow (Job genesis)";

    public GitflowBuilderDescriptor()
    {
	super(JobsByGitBranchBuilder.class);
	load();
    }

    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item project, @QueryParameter String url)
    {
	if (project == null || !project.hasPermission(Item.CONFIGURE))
	{
	    return new StandardListBoxModel();
	}
	return new StandardListBoxModel().withEmptySelection().withMatching(GitClient.CREDENTIALS_MATCHER,
		CredentialsProvider.lookupCredentials(StandardCredentials.class, project, ACL.SYSTEM, GitURIRequirementsBuilder.fromUri(url).build()));
    }

    public FormValidation doCheckTemplateJob(@QueryParameter String value) throws IOException, ServletException
    {
	if (value.length() == 0)
	{
	    return FormValidation.error("Please set a template job");
	}
	else
	{
	    Job<?, ?> newJobAlreadyExisting = (Job<?, ?>) Jenkins.getInstance().getItem(value);
	    if (newJobAlreadyExisting == null)
	    {
		return FormValidation.error("Job not found: " + value);
	    }
	}

	return FormValidation.ok();
    }

    public FormValidation doCheckJobNameTemplate(@QueryParameter String value) throws IOException, ServletException
    {
	return FormValidation.ok();
    }

    public boolean isApplicable(@SuppressWarnings("rawtypes") Class<? extends AbstractProject> aClass)
    {
	return true;
    }

    /**
     * This human readable name is used in the configuration screen.
     */
    public String getDisplayName()
    {
	return PLUGIN_TITLE;
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject formData) throws FormException
    {
	save();
	return super.configure(req, formData);
    }
}
