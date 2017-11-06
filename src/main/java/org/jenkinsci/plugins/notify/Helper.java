package org.jenkinsci.plugins.notify;

import hudson.model.AbstractBuild;
import hudson.model.Result;

public class Helper {
	private AbstractBuild build;

	public Helper(AbstractBuild build) {
		this.build = build;
	}

	public boolean isSuccess() {
		return isSuccess(build);
	}

	private boolean isSuccess(AbstractBuild myBuild) {
		return myBuild.getResult().equals(Result.SUCCESS) || myBuild.getResult().equals(Result.UNSTABLE);
	}

	public boolean isLastThreeFailed() {
	    return isFailure() &&
	        isPreviousFailure(build) && 
	          isPreviousFailure(build.getPreviousBuild());
	    }
	
	private boolean isPreviousFailure(AbstractBuild myBuild) {
		AbstractBuild prevBuild = myBuild.getPreviousBuild();
		return prevBuild !=null && !isSuccess(prevBuild);
    }


	public boolean isFailure() {
		return !isSuccess(build);
	}

	public boolean isPreviousSuccess() {
		if(build.getPreviousBuild()==null) {
			return true;
		}
		return isSuccess(build.getPreviousBuild());
	}

	public boolean isPreviousFailure() {
		return !isPreviousSuccess();
	}
	
	public boolean isRecovering() {
		return isPreviousFailure() && isSuccess();
	}

	public boolean isWarning() {
		return isPreviousSuccess() && isFailure();
	}
}
