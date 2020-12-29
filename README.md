# BoardSyncer
This application retrieves the issues on a Jira board and synchronizes this to cards on a GitHub Project Board in the OrdinaNederland GitHub organization. The user needs to provide his/her credentials for both Jira and GitHub (concealed in the environment variables of the IDE).

To use it, adjust the following <i>class variables</i> in the BoardsyncerAppliation class:
1. <u>String jiraUrl</u>: the URL to your Jira board, up to and including ".net". E.g. in the case of the Jobcrawler it's: "https://jobcrawler.atlassian.net".
2. <u>String projectName</u>: the name of the GitHub project found under the OrdinaNederland GitHub organisation. 
3. <u>boolean deleteExtras</u>: set this to true or false for whether you want extra cards on the GitHub board (which are not on the Jira board) to be deleted.

And the following <i>environment variables</i> in your IDE (i.e. in IntelliJ, go to Run -> Edit Configurations... -> Environment variables):
1. <u>JIRA_TOKEN</u>: First, obtain an Atlassian API token here: https://id.atlassian.com/manage-profile/security/api-tokens. Then, make a base64 encoding of: {your username}:{your token}. To make a base64 encoding, use e.g. https://www.base64encode.org/. Store the encoded string as JIRA_TOKEN.  
2. <u>GITHUB_TOKEN</u>: Obtain a GitHub API token here: https://github.com/settings/tokens, and store it as GITHUB_TOKEN.

For any additional questions, contact GitHub user https://github.com/arti00git.
