package de.osthus.jenkins.plugins.git;

import java.util.logging.Logger;

import hudson.Plugin;

public class MultibranchJobCreatorPlugin extends Plugin
{
    private final static Logger LOG = Logger.getLogger(MultibranchJobCreatorPlugin.class.getName());

    public void start() throws Exception
    {
	LOG.info("starting gitflow plugin");
    }
}
