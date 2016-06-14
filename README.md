# jenkins-multibranch-job-creator-plugin
Creates Jobs based on a template for each branch in a Git repository which matches a regular expression and the last commit is not longer ago than maxAge.

If the last commit is longer ago than maxAge, the branch is considered abandoned and no job for this branch will be created.

If a job exists for a branch which has been removed or is considered abandoned, the corresponsing job will be deleted from Jenkins.

Two commonly used examples for regular expressions:
- Build all branches except "master" and "develop": ^((?!origin/master)(?!origin/develop).)*$
- Build all feature branches: .*/feature/.*
