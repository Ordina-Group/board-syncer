# BoardSyncer
This application retrieves the issues on a Jira board and synchronizes this to cards on a GitHub Project Board in the OrdinaNederland GitHub organization. The user needs to provide his/her credentials for both Jira and GitHub (concealed in the environment variables of the IDE).

To use it, define the following <i>environment variables</i> in your IDE (i.e. in IntelliJ, go to Run -> Edit Configurations... -> Environment variables):
1. `JIRA_URL`: the URL to your Jira board, up to and including ".net". E.g. in the case of the Jobcrawler it's: https://jobcrawler.atlassian.net.
2. `PROJECT_NAME`: the name of the GitHub project found under the OrdinaNederland GitHub organisation. 
3. `JIRA_TOKEN`: First, obtain an Atlassian API token here: https://id.atlassian.com/manage-profile/security/api-tokens. Then, make a base64 encoding of: {your username}:{your token}. To make a base64 encoding, use e.g. https://www.base64encode.org/. Store this as your JIRA_TOKEN.  
4. `GITHUB_TOKEN`: Obtain a GitHub API token here: https://github.com/settings/tokens.
5. `DELETE_EXTRAS`: (optional) set this to true or false for whether you want extra cards on the GitHub board (which are not on the Jira board) to be deleted. If undefined, this is set to false.

For any additional questions, contact GitHub user https://github.com/arti00git.
