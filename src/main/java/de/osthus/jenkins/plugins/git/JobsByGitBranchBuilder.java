package de.osthus.jenkins.plugins.git;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.GitURIRequirementsBuilder;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import hudson.plugins.git.Branch;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

public class JobsByGitBranchBuilder extends Builder {

    private static final String PLUGIN_TITLE = "Create jobs for Git branches";
    // private static final String PROPERTY_BRANCH_NAME = "${branch.name}";

    private final Pattern findBranchNamePattern = Pattern.compile(".*\\((.*)\\)");

    private final String branchRegex;
    private final String checkoutDir;
    private final String templateJob;
    private final String credentialsId;
    private final String baseJobName;

    @DataBoundConstructor
    public JobsByGitBranchBuilder(String branchRegex, String checkoutDir, String templateJob, String credentialsId, String baseJobName) {
	this.branchRegex = branchRegex;
	this.checkoutDir = checkoutDir;
	this.templateJob = templateJob;
	this.credentialsId = credentialsId;
	this.baseJobName = baseJobName;
    }

    public String getBranchRegex() {
	return branchRegex;
    }

    public String getCheckoutDir() {
	return checkoutDir;
    }

    public String getTemplateJob() {
	return templateJob;
    }

    public String getCredentialsId() {
	return credentialsId;
    }

    public String getBaseJobName() {
	return baseJobName;
    }

    @Override
    public boolean perform(@SuppressWarnings("rawtypes") AbstractBuild build, Launcher launcher, BuildListener listener) {
	String repoDir = Util.removeTrailingSlash(build.getWorkspace().getRemote()) + "/" + getCheckoutDir();
	Jenkins jenkins = Jenkins.getInstance();
	try {
	    GitClient jGitClient = getGitClient(build, listener);

	    Job<?, ?> templateJob = (Job<?, ?>) jenkins.getItem(getTemplateJob());
	    if (branchRegex != null && branchRegex.length() > 0) {
		listener.getLogger().println("looking for remote branches matching: " + branchRegex);
	    }
	    List<String> remoteBranches = listRemoteBranches(jGitClient, repoDir, credentialsId);
	    for (String branch : remoteBranches) {
		if (branchRegex != null && !branchRegex.trim().equals("") && !branch.matches(branchRegex)) {
		    continue;
		}
		listener.getLogger().println("Found matching branch: " + branch);

		String xmlConfig = templateJob.getConfigFile().asString();
		String jobName = getBaseJobName() + " (" + branch.trim().replaceAll("/", "_") + ")";// replaceAll("origin/",
												    // "").

		Job<?, ?> newJobAlreadyExisting = (Job<?, ?>) jenkins.getItem(jobName);
		if (newJobAlreadyExisting != null) {
		    listener.getLogger().println("Job already exists, removing job: " + jobName);

		    jenkins.remove((TopLevelItem) newJobAlreadyExisting);
		}

		// replace GIT branch in config
		xmlConfig = xmlConfig.replaceAll("(?s)\\Q<hudson.plugins.git.BranchSpec>\\E.*?\\Q</hudson.plugins.git.BranchSpec>\\E", "<hudson.plugins.git.BranchSpec><name>"
			+ branch.trim() + "</name></hudson.plugins.git.BranchSpec>");

		xmlConfig = xmlConfig.replaceAll("<disabled>true</disabled>", "<disabled>false</disabled>");

		listener.getLogger().println("creating/updating job: " + jobName);
		jenkins.createProjectFromXML(jobName, new ByteArrayInputStream(xmlConfig.getBytes()));
	    }

	    removeJobsLinkedToDeletedBranches(remoteBranches, listener.getLogger());
	} catch (Exception e) {
	    e.printStackTrace();
	    throw new RuntimeException("unexpected error: ", e);
	}

	return true;
    }

    private void removeJobsLinkedToDeletedBranches(List<String> remoteBranches, PrintStream logger) {
	Jenkins jenkins = Jenkins.getInstance();
	for (String jobName : jenkins.getJobNames()) {
	    if (jobName.matches(baseJobName + " \\(.*\\)")) {
		Matcher matcher = findBranchNamePattern.matcher(jobName);
		if (matcher.find()) {
		    String branchOfCurrentJob = matcher.group(1).replaceAll("_", "/");
		    if (!remoteBranches.contains(branchOfCurrentJob)) {
			try {
			    Job<?, ?> job = (Job<?, ?>) jenkins.getItem(jobName);
			    jenkins.remove((TopLevelItem) job);
			    logger.println("Detected deleted branch, removing linked job: " + jobName);
			} catch (IOException e) {
			    e.printStackTrace();
			}
		    }
		}
	    }
	}
    }

    private GitClient getGitClient(@SuppressWarnings("rawtypes") AbstractBuild build, BuildListener listener) throws Exception {
	// File workspace = new File(build.getWorkspace().getRemote());
	listener.getLogger().println("Workspace location: " + build.getWorkspace());
	// DescriptorImpl gitTools =
	// Jenkins.getInstance().getDescriptorByType(GitTool.DescriptorImpl.class);
	EnvVars envVars = build.getCharacteristicEnvVars();
	GitClient jgitClient = Git.with(listener, envVars).in(build.getWorkspace()).getClient();

	return jgitClient;
    }

    private static List<String> listRemoteBranches(GitClient jGitClient, String repoDir, String credentialsId) throws Exception {
	Set<Branch> remoteBranches = jGitClient.getRemoteBranches();
	List<String> branchNameList = new ArrayList<String>();
	for (Branch branch : remoteBranches) {
	    if (!"HEAD".equals(branch.getName())) {
		branchNameList.add(branch.getName());
	    }
	}
	return branchNameList;
    }


    @Override
    public JobsByGitBranchBuilderDescriptor getDescriptor() {
	return (JobsByGitBranchBuilderDescriptor) super.getDescriptor();
    }

    @Extension
    public static final class JobsByGitBranchBuilderDescriptor extends BuildStepDescriptor<Builder> {
	public JobsByGitBranchBuilderDescriptor() {
	    load();
	}

	public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item project, @QueryParameter String url) {
	    if (project == null || !project.hasPermission(Item.CONFIGURE)) {
		return new StandardListBoxModel();
	    }
	    return new StandardListBoxModel().withEmptySelection().withMatching(GitClient.CREDENTIALS_MATCHER,
		    CredentialsProvider.lookupCredentials(StandardCredentials.class, project, ACL.SYSTEM, GitURIRequirementsBuilder.fromUri(url).build()));
	}

	public FormValidation doCheckTemplateJob(@QueryParameter String value) throws IOException, ServletException {
	    if (value.length() == 0) {
		return FormValidation.error("Please set a template job");
	    } else {
		Job<?, ?> newJobAlreadyExisting = (Job<?, ?>) Jenkins.getInstance().getItem(value);
		if (newJobAlreadyExisting == null) {
		    return FormValidation.error("Job not found: " + value);
		}
	    }

	    return FormValidation.ok();
	}

	public FormValidation doCheckJobNameTemplate(@QueryParameter String value) throws IOException, ServletException {
	    return FormValidation.ok();
	}

	public boolean isApplicable(@SuppressWarnings("rawtypes") Class<? extends AbstractProject> aClass) {
	    return true;
	}

	/**
	 * This human readable name is used in the configuration screen.
	 */
	public String getDisplayName() {
	    return PLUGIN_TITLE;
	}

	@Override
	public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
	    save();
	    return super.configure(req, formData);
	}
    }
}
