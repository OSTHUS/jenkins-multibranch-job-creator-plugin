package de.osthus.jenkins.plugins.git;

import java.util.logging.Logger;

import hudson.Plugin;

public class GitflowPlugin extends Plugin
{
    private final static Logger LOG = Logger.getLogger(GitflowPlugin.class.getName());

    public void start() throws Exception
    {
	LOG.info("starting gitflow plugin");
    }
}
