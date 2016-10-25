package de.osthus.jenkins.plugins.git;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.jenkinsci.plugins.gitclient.JGitTool;
import org.jenkinsci.plugins.gitclient.RepositoryCallback;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction.TransientActionFactoryImpl;
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
import jenkins.model.DirectlyModifiableTopLevelItemGroup;
import jenkins.model.Jenkins;
import jenkins.model.ModifiableTopLevelItemGroup;
import net.sf.json.JSONObject;

public class JobsByGitBranchBuilder extends Builder implements Serializable {

	// private static final String PLUGIN_TITLE = "Gitflow (Job genesis)";
	// private static final String PROPERTY_BRANCH_NAME = "${branch.name}";

	private static final long serialVersionUID = 1L;

	private final Pattern findBranchNamePattern = Pattern.compile(".*\\((.*)\\)");

	private final String branchRegex;
	private final String checkoutDir;
	private final String templateJob;
	private final String targetFolder;
	private final String credentialsId;
	private final String baseJobName;
	private final long maxBranchAgeInDays;
	private final long maxBranchAgeInMillis;

	@DataBoundConstructor
	public JobsByGitBranchBuilder(String branchRegex, String checkoutDir, String templateJob, String targetFolder, String credentialsId,
			String baseJobName, long maxBranchAgeInDays) {
		this.branchRegex = branchRegex;
		this.checkoutDir = checkoutDir;
		this.templateJob = templateJob;
		this.targetFolder = targetFolder;
		this.credentialsId = credentialsId;
		this.baseJobName = baseJobName;
		this.maxBranchAgeInDays = maxBranchAgeInDays;
		this.maxBranchAgeInMillis = maxBranchAgeInDays * 24l * 60l * 60l * 1000l;
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

	public long getMaxBranchAgeInDays() {
		return maxBranchAgeInDays;
	}
	
	public String getTargetFolder() {
		return targetFolder;
	}
	
//	private ModifiableTopLevelItemGroup getJenkins() {
//		return Jenkins.getInstance();
//	}
	
	public DirectlyModifiableTopLevelItemGroup findTargetFolder() {
		if ( targetFolder != null && targetFolder.length() > 0 ){
			return (Folder) Jenkins.getInstance().getItemByFullName(targetFolder);
		} else {
			//return the folder where the template job is located in
			Item i = Jenkins.getInstance().getItemByFullName(templateJob);
			if ( !i.getFullName().contains("/") ) {
				return Jenkins.getInstance();
			}
			List<String> pathSplit = Arrays.asList(i.getFullName().split("/"));
			pathSplit = pathSplit.subList(0, pathSplit.size()-1);
			StringBuilder targetFolderSB = new StringBuilder();
			for ( String pathPart: pathSplit ) {
				targetFolderSB.append("/").append(pathPart);
			}
			return (Folder) Jenkins.getInstance().getItemByFullName(targetFolderSB.substring(1));
		}
	}

	@Override
	public boolean perform(@SuppressWarnings("rawtypes") AbstractBuild build, Launcher launcher,
			BuildListener listener) {
		try {
			final Jenkins jenkins = Jenkins.getInstance();
			final PrintStream logger = listener.getLogger();
			final GitClient jGitClient = getGitClient(build, listener);
			final Set<Branch> allRemoteBranches = jGitClient.getRemoteBranches();
			
			List<BranchInfo> remoteBranches = jGitClient.withRepository(new RepositoryCallback<List<BranchInfo>>() {		
				private static final long serialVersionUID = 1L;
				
				@Override
				public List<BranchInfo> invoke(Repository repo, VirtualChannel vc) throws IOException, InterruptedException {
					try {
						return listRemoteBranches(repo, allRemoteBranches);
					} catch (Exception e) {
						e.printStackTrace();
						throw new IOException(e);
					}					
				}
			});
			
			//remove all invalid branches and log the reason messages
			for (Iterator<BranchInfo> iterator = remoteBranches.iterator(); iterator.hasNext();) {
				BranchInfo branchInfo = iterator.next();
				logger.println("Found branch: " + branchInfo.getName() + ", last commit date: " + new Date(branchInfo.getLastCommit()));
				logger.println(branchInfo.getReasonMessage());
				if ( !branchInfo.isValid() ) {
					iterator.remove();
				}
			}

			for (BranchInfo branch : remoteBranches) {				
				if ( branch.isValid() ) {					
					String jobName = getBaseJobName() + "_(" + branch.getEscapedName() + ")";					
					removeExistingJob(jobName, jenkins, logger);
					createJobForBranch(jobName, branch, jenkins, logger, listener);
				}
			}

			// cleanup existing jobs
			removeJobsLinkedToDeletedOrAbandonedBranches(remoteBranches, logger);
		} catch (Exception e) {
			throw new RuntimeException("unexpected error: ", e);
		}

		return true;
	}
	
//	private Item findJobInAllFolders(String jobName, Jenkins jenkins) {
//		for ( Item i: jenkins.getAllItems() ) {
//			if ( i.getName().equals(jobName) ){
//				return i;
//			}
//		}
//		return null;
//	}
	

	/**
	 * create a job in jenkins
	 * 
	 * @param jobName
	 * @param branch
	 * @param jenkins
	 * @param logger
	 * @param listener 
	 * @throws IOException
	 */
	private void createJobForBranch(String jobName, BranchInfo branch, Jenkins jenkins, PrintStream logger, BuildListener listener)
			throws IOException {
		Job<?, ?> templateJob = (Job<?, ?>) jenkins.getItemByFullName(getTemplateJob());
		
		if ( templateJob == null || templateJob.getConfigFile() == null ) {
			listener.fatalError("Template job not found: " + getTemplateJob());
			
			logger.println("Listing available jobs:");
			for (Item i:  jenkins.getAllItems() ) {
				logger.println("Item: " + i.getFullName());
			}
		}
		String xmlConfig = templateJob.getConfigFile().asString();

		// replace GIT branch in config
		xmlConfig = xmlConfig.replaceAll(
				"(?s)\\Q<hudson.plugins.git.BranchSpec>\\E.*?\\Q</hudson.plugins.git.BranchSpec>\\E",
				"<hudson.plugins.git.BranchSpec><name>" + branch.getName().trim() + "</name></hudson.plugins.git.BranchSpec>");

		xmlConfig = xmlConfig.replaceAll("<disabled>true</disabled>", "<disabled>false</disabled>");
		logger.println("creating/updating job: " + jobName);
		
		ModifiableTopLevelItemGroup topLevelItemGroup = findTargetFolder();
		topLevelItemGroup.createProjectFromXML(jobName, new ByteArrayInputStream(xmlConfig.getBytes()));
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
	private boolean removeExistingJob(String jobName, Jenkins jenkins, PrintStream logger) throws IOException {
		boolean jobRemoved = false;
		
		DirectlyModifiableTopLevelItemGroup topLevelItemGroup = findTargetFolder();
//		Job<?, ?> newJobAlreadyExisting = (Job<?, ?>) jenkins.getItemByFullName(jobName);
		Job<?, ?> newJobAlreadyExisting = (Job<?, ?>) findTargetFolder().getItem(jobName);
		if (newJobAlreadyExisting != null) {
			logger.println("Removing existing job: " + newJobAlreadyExisting.getFullName());
			topLevelItemGroup.remove((TopLevelItem) newJobAlreadyExisting);
			jobRemoved = true;
		}
		return jobRemoved;
	}

	/**
	 * 
	 * @param remoteBranches
	 * @param logger
	 */
	private void removeJobsLinkedToDeletedOrAbandonedBranches(List<BranchInfo> remoteBranches, PrintStream logger) {
		Jenkins jenkins = Jenkins.getInstance();
		
		DirectlyModifiableTopLevelItemGroup topLevelItemGroup = findTargetFolder();
		
		for (Item item : topLevelItemGroup.getItems()) {
			String jobName = item.getName();
			if (jobName.matches(baseJobName + "_\\(.*\\)")) {
				Matcher matcher = findBranchNamePattern.matcher(jobName);
				if (matcher.find()) {
					String branchOfCurrentJob = matcher.group(1);
					if (!remoteBranches.contains(BranchInfo.withName(branchOfCurrentJob))) {
						try {
							//Job<?, ?> job = (Job<?, ?>) jenkins.getItemByFullName(item.getFullName());
							topLevelItemGroup.remove((TopLevelItem) item);
							logger.println("Detected deleted/abandoned branch [" + branchOfCurrentJob + "], removing linked job: "
									+ jobName);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
		
		
//		for (String jobName : jenkins.getJobNames()) {
//			if (jobName.matches(baseJobName + " \\(.*\\)")) {
//				Matcher matcher = findBranchNamePattern.matcher(jobName);
//				if (matcher.find()) {
//					String branchOfCurrentJob = matcher.group(1);
//					if (!remoteBranches.contains(BranchInfo.withName(branchOfCurrentJob))) {
//						try {
//							Job<?, ?> job = (Job<?, ?>) jenkins.getItem(jobName);
//							jenkins.remove((TopLevelItem) job);
//							logger.println("Detected deleted branch [" + branchOfCurrentJob + "], removing linked job: "
//									+ jobName);
//						} catch (IOException e) {
//							e.printStackTrace();
//						}
//					}
//				}
//			}
//		}
	}

	/**
	 * 
	 * @param build
	 * @param listener
	 * @return
	 * @throws Exception
	 */
	private GitClient getGitClient(@SuppressWarnings("rawtypes") AbstractBuild build, BuildListener listener)
			throws Exception {
		listener.getLogger().println("Workspace location: " + build.getWorkspace());
		EnvVars envVars = build.getCharacteristicEnvVars();
		GitClient jgitClient = Git.with(listener, envVars).in(build.getWorkspace()).using(JGitTool.MAGIC_EXENAME).getClient();
		
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
	private List<BranchInfo> listRemoteBranches(Repository repository, Set<Branch> remoteBranches) throws Exception 
	{
		List<BranchInfo> branchList = new ArrayList<BranchInfo>();
		for (Branch branch : remoteBranches) {
			if (!"HEAD".equals(branch.getName())) {
				RevWalk revWalk = new RevWalk(repository);
				revWalk.markStart(revWalk.parseCommit(repository.resolve(branch.getSHA1String())));

				long lastCommit = -1;
				for (Iterator<RevCommit> iterator = revWalk.iterator(); iterator.hasNext();) {
					RevCommit rev = iterator.next();
					if (rev.getCommitTime() > lastCommit) {
						lastCommit = (long) rev.getCommitTime() * 1000L;
					}
				}
				BranchInfo branchInfo = new BranchInfo(branch.getName(), lastCommit);
				branchList.add(branchInfo);

				if (branchRegex == null || branchRegex.trim().equals("") || branchInfo.getName().matches(branchRegex)) {
					if (maxBranchAgeInDays <= 0 || System.currentTimeMillis() - lastCommit < maxBranchAgeInMillis) {
						branchInfo.setValid(true);
						branchInfo.setReasonMessage(" - job will be created or updated");						
					} else {
						branchInfo.setValid(false);
						branchInfo.setReasonMessage(" - abandoned, older than " + maxBranchAgeInDays + " days! Consider deleting this branch!");
					}
				} else {
					branchInfo.setReasonMessage(" - name does not match pattern: " + branchRegex);
				}
			}
		}
		return branchList;
	}

	@Override
	public MultibranchJobCreatorBuilderDescriptor getDescriptor() {
		return (MultibranchJobCreatorBuilderDescriptor) super.getDescriptor();
	}

	@Extension
	public static class MultibranchJobCreatorBuilderDescriptor extends BuildStepDescriptor<Builder> {
		private static final String PLUGIN_TITLE = "Multibranch job creation";

		public MultibranchJobCreatorBuilderDescriptor() {
			super();
			load();
		}

		public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item project, @QueryParameter String url) {
			if (project == null || !project.hasPermission(Item.CONFIGURE)) {
				return new StandardListBoxModel();
			}
			return new StandardListBoxModel().withEmptySelection().withMatching(GitClient.CREDENTIALS_MATCHER,
					CredentialsProvider.lookupCredentials(StandardCredentials.class, project, ACL.SYSTEM,
							GitURIRequirementsBuilder.fromUri(url).build()));
		}

		public FormValidation doCheckTemplateJob(@QueryParameter String value) throws IOException, ServletException {
			if (value.length() == 0) {
				return FormValidation.error("Please set a template job");
			} else {				
				Job<?, ?> newJobAlreadyExisting = (Job<?, ?>) Jenkins.getInstance().getItemByFullName(value);
				if (newJobAlreadyExisting == null) {
					return FormValidation.error("Job not found: " + value);
				}
			}

			return FormValidation.ok();
		}

		
		public FormValidation doCheckTargetFolder(@QueryParameter String value) throws IOException, ServletException {
			if (value.length() != 0) {				
				Item folderItem = Jenkins.getInstance().getItemByFullName(value);
				
				if ( folderItem == null ) {
					return FormValidation.error("Folder not found: " + value);
				}
				
				if ( !(folderItem instanceof Folder) ) {
					return FormValidation.error("Not a folder: " + value);
				}				
			}

			return FormValidation.ok();
		}
		
		public FormValidation doCheckJobNameTemplate(@QueryParameter String value)
				throws IOException, ServletException {
			return FormValidation.ok();
		}

		public boolean isApplicable(@SuppressWarnings("rawtypes") Class<? extends AbstractProject> jobType) {
			return FreeStyleProject.class.isAssignableFrom(jobType);
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
