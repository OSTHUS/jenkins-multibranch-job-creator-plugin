package de.osthus.jenkins.plugins.git;

import java.io.Serializable;

public class BranchInfo implements Serializable {
	private static final long serialVersionUID = 1L;

	final private String name;
	final private String escapedName;
	final private long lastCommit;

	private boolean valid;
	private String reasonMessage;

	public BranchInfo(String name, long lastCommit) {
		this.name = name;
		this.lastCommit = lastCommit;
		this.escapedName = name.trim().replaceAll("origin/", "").replaceAll("/", "_");
	}

	public static BranchInfo withName(String name) {
		return new BranchInfo(name, -1);
	}

	public String getName() {
		return name;
	}

	public String getEscapedName() {
		return escapedName;
	}

	public long getLastCommit() {
		return lastCommit;
	}

	public String getReasonMessage() {
		return reasonMessage;
	}

	public boolean isValid() {
		return valid;
	}

	public void setValid(boolean valid) {
		this.valid = valid;
	}

	public void setReasonMessage(String reasonMessage) {
		this.reasonMessage = reasonMessage;
	}

	@Override
	public String toString() {
		return getEscapedName();
	}

	@Override
	public int hashCode() {
		return escapedName.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		BranchInfo other = (BranchInfo) obj;
		if (escapedName == null) {
			if (other.escapedName != null)
				return false;
		} else if (!escapedName.equals(other.escapedName))
			return false;
		return true;
	}

}
