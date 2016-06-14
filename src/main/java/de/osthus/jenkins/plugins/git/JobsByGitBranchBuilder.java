package de.osthus.jenkins.plugins.git;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.GitURIRequirementsBuilder;
import org.jenkinsci.plugins.gitclient.RepositoryCallback;
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
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import hudson.plugins.git.Branch;
import hudson.remoting.VirtualChannel;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

public class JobsByGitBranchBuilder extends Builder
{

    // private static final String PLUGIN_TITLE = "Gitflow (Job genesis)";
    // private static final String PROPERTY_BRANCH_NAME = "${branch.name}";

    private final Pattern findBranchNamePattern = Pattern.compile(".*\\((.*)\\)");

    private final String branchRegex;
    private final String checkoutDir;
    private final String templateJob;
    private final String credentialsId;
    private final String baseJobName;
    private final long maxBranchAgeInDays;
    private final long maxBranchAgeInMillis;

    @DataBoundConstructor
    public JobsByGitBranchBuilder(String branchRegex, String checkoutDir, String templateJob, String credentialsId, String baseJobName, long maxBranchAgeInDays)
    {
	this.branchRegex = branchRegex;
	this.checkoutDir = checkoutDir;
	this.templateJob = templateJob;
	this.credentialsId = credentialsId;
	this.baseJobName = baseJobName;
	this.maxBranchAgeInDays = maxBranchAgeInDays;
	this.maxBranchAgeInMillis = maxBranchAgeInDays * 24l * 60l * 60l * 1000l;
    }

    public String getBranchRegex()
    {
	return branchRegex;
    }

    public String getCheckoutDir()
    {
	return checkoutDir;
    }

    public String getTemplateJob()
    {
	return templateJob;
    }

    public String getCredentialsId()
    {
	return credentialsId;
    }

    public String getBaseJobName()
    {
	return baseJobName;
    }

    public long getMaxBranchAgeInDays()
    {
	return maxBranchAgeInDays;
    }

    @Override
    public boolean perform(@SuppressWarnings("rawtypes") AbstractBuild build, Launcher launcher, BuildListener listener)
    {
	try
	{
	    final Jenkins jenkins = Jenkins.getInstance();
	    final PrintStream logger = listener.getLogger();
	    final GitClient jGitClient = getGitClient(build, listener);

	    /*
	     * get a list of considered remote branches. i.e. last commit not longer than defined days and name matches given pattern
	     */
	    List<BranchInfo> remoteBranches = jGitClient.withRepository(new RepositoryCallback<List<BranchInfo>>()
	    {
		private static final long serialVersionUID = 1L;

		@Override
		public List<BranchInfo> invoke(Repository repository, VirtualChannel channel) throws IOException, InterruptedException
		{
		    try
		    {
			return listRemoteBranches(repository, jGitClient, logger);
		    }
		    catch (Exception e)
		    {
			throw new RuntimeException("unexpected error: ", e);
		    }
		}
	    });

	    for (BranchInfo branch : remoteBranches)
	    {
		String jobName = getBaseJobName() + " (" + branch.getEscapedName() + ")";
		removeExistingJob(jobName, jenkins, logger);
		createJobForBranch(jobName, branch, jenkins, logger);
	    }

	    // cleanup existing jobs
	    removeJobsLinkedToDeletedOrAbandonedBranches(remoteBranches, logger);
	}
	catch (Exception e)
	{
	    throw new RuntimeException("unexpected error: ", e);
	}

	return true;
    }

    /**
     * create a job in jenkins
     * 
     * @param jobName
     * @param branch
     * @param jenkins
     * @param logger
     * @throws IOException
     */
    private void createJobForBranch(String jobName, BranchInfo branch, Jenkins jenkins, PrintStream logger) throws IOException
    {
	Job<?, ?> templateJob = (Job<?, ?>) jenkins.getItem(getTemplateJob());
	String xmlConfig = templateJob.getConfigFile().asString();

	// replace GIT branch in config
	xmlConfig = xmlConfig.replaceAll("(?s)\\Q<hudson.plugins.git.BranchSpec>\\E.*?\\Q</hudson.plugins.git.BranchSpec>\\E",
		"<hudson.plugins.git.BranchSpec><name>" + branch.getName().trim() + "</name></hudson.plugins.git.BranchSpec>");

	xmlConfig = xmlConfig.replaceAll("<disabled>true</disabled>", "<disabled>false</disabled>");

	logger.println("creating/updating job: " + jobName);
	jenkins.createProjectFromXML(jobName, new ByteArrayInputStream(xmlConfig.getBytes()));
    }

    /**
     * remove a job in jenkins, does nothing if the job does not exists
     * 
     * @param jenkins
     * @param jobName
     * @param logger
     * @return true if a job was deleted, false if no such job existed
     * @throws IOException
     */
    private boolean removeExistingJob(String jobName, Jenkins jenkins, PrintStream logger) throws IOException
    {
	boolean jobRemoved = false;
	Job<?, ?> newJobAlreadyExisting = (Job<?, ?>) jenkins.getItem(jobName);
	if (newJobAlreadyExisting != null)
	{
	    logger.println("Removing existing job: " + jobName);
	    jenkins.remove((TopLevelItem) newJobAlreadyExisting);
	    jobRemoved = true;
	}
	return jobRemoved;
    }

    /**
     * 
     * @param remoteBranches
     * @param logger
     */
    private void removeJobsLinkedToDeletedOrAbandonedBranches(List<BranchInfo> remoteBranches, PrintStream logger)
    {
	Jenkins jenkins = Jenkins.getInstance();
	for (String jobName : jenkins.getJobNames())
	{
	    if (jobName.matches(baseJobName + " \\(.*\\)"))
	    {
		Matcher matcher = findBranchNamePattern.matcher(jobName);
		if (matcher.find())
		{
		    String branchOfCurrentJob = matcher.group(1);
		    if (!remoteBranches.contains(new BranchInfo(branchOfCurrentJob, -1)))
		    {
			try
			{
			    Job<?, ?> job = (Job<?, ?>) jenkins.getItem(jobName);
			    jenkins.remove((TopLevelItem) job);
			    logger.println("Detected deleted branch [" + branchOfCurrentJob + "], removing linked job: " + jobName);
			}
			catch (IOException e)
			{
			    e.printStackTrace();
			}
		    }
		}
	    }
	}
    }

    /**
     * 
     * @param build
     * @param listener
     * @return
     * @throws Exception
     */
    private GitClient getGitClient(@SuppressWarnings("rawtypes") AbstractBuild build, BuildListener listener) throws Exception
    {
	listener.getLogger().println("Workspace location: " + build.getWorkspace());
	EnvVars envVars = build.getCharacteristicEnvVars();
	GitClient jgitClient = Git.with(listener, envVars).in(build.getWorkspace()).getClient();

	return jgitClient;
    }

    /**
     * 
     * @param repository
     * @param jGitClient
     * @param logger
     * @return
     * @throws Exception
     */
    private List<BranchInfo> listRemoteBranches(Repository repository, GitClient jGitClient, PrintStream logger) throws Exception
    {
	Set<Branch> remoteBranches = jGitClient.getRemoteBranches();
	List<BranchInfo> branchList = new ArrayList<BranchInfo>();
	for (Branch branch : remoteBranches)
	{
	    if (!"HEAD".equals(branch.getName()))
	    {
		// Repository repository = jGitClient.getRepository();
		RevWalk revWalk = new RevWalk(repository);
		revWalk.markStart(revWalk.parseCommit(repository.resolve(branch.getSHA1String())));

		long lastCommit = -1;
		for (Iterator<RevCommit> iterator = revWalk.iterator(); iterator.hasNext();)
		{
		    RevCommit rev = iterator.next();
		    if (rev.getCommitTime() > lastCommit)
		    {
			lastCommit = (long) rev.getCommitTime() * 1000L;
		    }
		}
		BranchInfo branchInfo = new BranchInfo(branch.getName(), lastCommit);

		logger.print("Found branch: " + branchInfo.getName() + ", last commit date: " + new Date(branchInfo.getLastCommit()));

		if (branchRegex == null || branchRegex.trim().equals("") || branchInfo.getName().matches(branchRegex))
		{
		    if (maxBranchAgeInDays <= 0 || System.currentTimeMillis() - lastCommit < maxBranchAgeInMillis)
		    {
			logger.println(" - job will be created or updated");
			branchList.add(branchInfo);
		    }
		    else
		    {
			logger.println(" - abandoned, older than " + maxBranchAgeInDays + " days!");
		    }
		}
		else
		{
		    logger.println(" - name does not match pattern: " + branchRegex);
		}
	    }
	}
	return branchList;
    }

    @Override
    public MultibranchJobCreatorBuilderDescriptor getDescriptor()
    {
	return (MultibranchJobCreatorBuilderDescriptor) super.getDescriptor();
    }

    @Extension
    public static class MultibranchJobCreatorBuilderDescriptor extends BuildStepDescriptor<Builder>
    {
	private static final String PLUGIN_TITLE = "Multibranch job creation";

	public MultibranchJobCreatorBuilderDescriptor()
	{
	    super();
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

	public boolean isApplicable(@SuppressWarnings("rawtypes") Class<? extends AbstractProject> jobType)
	{
	    return FreeStyleProject.class.isAssignableFrom(jobType);
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
}
