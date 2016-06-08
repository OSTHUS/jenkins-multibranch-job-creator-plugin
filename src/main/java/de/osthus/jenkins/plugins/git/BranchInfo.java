package de.osthus.jenkins.plugins.git;

public class BranchInfo
{
    final private String name;
    final private String escapedName;
    final private long lastCommit;

    public BranchInfo(String name, long lastCommit)
    {
	this.name = name;
	this.lastCommit = lastCommit;
	this.escapedName = name.trim().replaceAll("origin/", "").replaceAll("/", "_");
    }

    public String getName()
    {
	return name;
    }

    public String getEscapedName()
    {
	return escapedName;
    }

    public long getLastCommit()
    {
	return lastCommit;
    }

    @Override
    public String toString()
    {
	return getEscapedName();
    }

    @Override
    public int hashCode()
    {
	return escapedName.hashCode();
    }

    @Override
    public boolean equals(Object obj)
    {
	if (this == obj)
	    return true;
	if (obj == null)
	    return false;
	if (getClass() != obj.getClass())
	    return false;
	BranchInfo other = (BranchInfo) obj;
	if (escapedName == null)
	{
	    if (other.escapedName != null)
		return false;
	}
	else if (!escapedName.equals(other.escapedName))
	    return false;
	return true;
    }

}
