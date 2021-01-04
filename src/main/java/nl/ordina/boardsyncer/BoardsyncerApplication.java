package nl.ordina.boardsyncer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@SpringBootApplication
public class BoardsyncerApplication {

	@Value("${JIRA_URL}")
	private String jiraUrl;
	@Value("${PROJECT_NAME}")
	private String projectName;
	@Value("${JIRA_TOKEN}")
	private String jiraToken;
	@Value("${GITHUB_TOKEN}")
	private String gitHubToken;
	@Value("${DELETE_EXTRAS:false}")
	boolean deleteExtras;

	static RestTemplate restTemplate = new RestTemplate();
	static Map<String,String> replaceColumns = new HashMap<>();
	static String url2 = "https://api.github.com/orgs/OrdinaNederland/projects";

	static {
		replaceColumns.put("In Progress","Doing");
	}

	public static void main(String[] args) {
		SpringApplication.run(BoardsyncerApplication.class, args);
	}

/*		Requests performed by syncBoards (o is optional by deleteExtras boolean):
			Request to Jira:
			1: Retrieve issues
			Requests to GitHub (o are optional, to delete superfluous):
			2: Find project id
			3: Find column ID's
			4 (o): Delete unnecessary columns
			5: Add columns if necessary
			6: Obtain column ID's again
			7: Put the columns in order
			8: Per column:
				a: Find what cards are there
				b (o): For each card with a name that doesn't belong there, delete it.
				c: Update/add cards
				d: Find what cards are there again
				e: Sort cards				*/

	@Bean
	public void syncBoards() {
		HttpHeaders jiraHeaders = new HttpHeaders();
		jiraHeaders.add("Authorization", "Basic " + jiraToken);
		jiraHeaders.add("content-type", "application/json");
		HttpHeaders gitHubHeaders = new HttpHeaders();
		gitHubHeaders.add("Authorization", "token " + gitHubToken);
		gitHubHeaders.add("Accept", "application/vnd.github.inertia-preview+json");
		HttpEntity<String> ghRequestEmptyBody = new HttpEntity<>("", gitHubHeaders);

		//1. Obtain issues from Jira
		String url1 = jiraUrl + "/rest/api/latest/search?maxResults=";
		HttpEntity<String> request1 = new HttpEntity<>("", jiraHeaders);
		ResponseEntity<Map> response1 = restTemplate.exchange(url1 + "1000", HttpMethod.GET, request1, Map.class);
		ArrayList<Map<String, Object>> jsonIssues = (ArrayList<Map<String, Object>>) response1.getBody().get("issues");
		Map<String, Map<String, String>> issues = new HashMap<>();
		issues = saveIssues(jsonIssues, issues);
		Integer totalResults = (Integer) response1.getBody().get("total");
		Integer resultsPerPage = (Integer) response1.getBody().get("maxResults");
		int nrRequests = (int) Math.ceil(totalResults / resultsPerPage);
		for (int i = 1; i < nrRequests; i++) {
			ResponseEntity<Map> response = restTemplate.exchange(url1 + resultsPerPage + "&startAt=" + (i * resultsPerPage), HttpMethod.GET, request1, Map.class);
			jsonIssues = (ArrayList<Map<String, Object>>) response.getBody().get("issues");
			issues = saveIssues(jsonIssues, issues);
		}
		//2. Find Project id
		ResponseEntity<List> response2 = restTemplate.exchange(url2, HttpMethod.GET, ghRequestEmptyBody, List.class);
		Integer projectId = 0;
		for (int i = 0; i < response2.getBody().size(); i++) {
			Map<String, Object> project = (Map<String, Object>) response2.getBody().get(i);
			if (project.get("name").equals(projectName)) {
				projectId = (Integer) project.get("id");
			}
		}
		//3. Finding column names
		String url3 = "https://api.github.com/projects/" + projectId + "/columns";
		ResponseEntity<List> response3 = restTemplate.exchange(url3, HttpMethod.GET, ghRequestEmptyBody, List.class);
		Map<String, Integer> columnIds = new HashMap<>();
		for (int i = 0; i < response3.getBody().size(); i++) {
			Map<String, Object> column = (Map<String, Object>) response3.getBody().get(i);
			columnIds.put((String) column.get("name"), (Integer) column.get("id"));
		}
		//Establish desired column names
		Set<String> columnNamesJira = new HashSet<>();
		columnNamesJira.addAll(issues.keySet());
		ArrayList<String> desiredColumnNames = new ArrayList<>();
		desiredColumnNames.add("Backlog");	//At least these column names
		desiredColumnNames.add("To Do");
		desiredColumnNames.add("Doing");
		desiredColumnNames.add("Review");
		desiredColumnNames.add("Done");
		columnNamesJira.removeAll(desiredColumnNames);
		desiredColumnNames.addAll(columnNamesJira);	//And any additional ones from Jira
		//4. Delete all columns if deleteExtras is on (what needs to be added will be added later)
		if(deleteExtras) {
			columnIds.forEach((String colName, Integer colId) -> {
					String url4 = "https://api.github.com/projects/columns/" + colId;
					ResponseEntity<List> response4 = restTemplate.exchange(url4, HttpMethod.DELETE, ghRequestEmptyBody, List.class);
				}
			);
			columnIds = new HashMap<>();
		}
		//5. Add columns that aren't there yet
		for (String s : desiredColumnNames) {
			if (!columnIds.containsKey(s)) {
				String url5 = "https://api.github.com/projects/" + projectId + "/columns";
				String body5 = "{\"name\": \"" + s + "\"}";
				HttpEntity<String> request5 = new HttpEntity<>(body5, gitHubHeaders);
				ResponseEntity<Map> response5 = restTemplate.exchange(url5, HttpMethod.POST, request5, Map.class);
				columnIds.put(s, (Integer) response5.getBody().get("id"));
			}
		}
		//6. Finding column names again
		String url6 = "https://api.github.com/projects/" + projectId + "/columns";
		ResponseEntity<List> response6 = restTemplate.exchange(url6, HttpMethod.GET, ghRequestEmptyBody, List.class);
		columnIds = new HashMap<>();
		for (int i = 0; i < response6.getBody().size(); i++) {
			Map<String, Object> column = (Map<String, Object>) response6.getBody().get(i);
			columnIds.put((String) column.get("name"), (Integer) column.get("id"));
		}
		//7. Move columns to the right order
		for (int i = 1; i < desiredColumnNames.size(); i++) {
			String url7 = "https://api.github.com/projects/columns/" + columnIds.get(desiredColumnNames.get(i)) + "/moves";
			String body7 = "{\"position\": \"after:" + columnIds.get(desiredColumnNames.get(i - 1)) + "\"}";
			HttpEntity<String> request7 = new HttpEntity<>(body7, gitHubHeaders);
			ResponseEntity<Map> response7 = restTemplate.exchange(url7, HttpMethod.POST, request7, Map.class);
		}
		//8a. Per column, obtain cards. Id and text
		for (int i = 0; i < desiredColumnNames.size(); i++) {    //Loops through columns to get cards
			Map<String, Integer> cards = getCardsInColumn(columnIds.get(desiredColumnNames.get(i)),ghRequestEmptyBody);
			Map<String, String> issues4column = issues.get(desiredColumnNames.get(i));
			if (!deleteExtras) {	//Only if deleteExtras is false, the below is necessary (otherwise the columns were deleted earlier)
				cards.forEach((String issueTitle, Integer cardId) -> { // 8b. Loops through cards, deletes ones with same name
					if (issues4column.containsKey(issueTitle)) {	   // as Jira issues (because they're added again later)
						String url8b = "https://api.github.com/projects/columns/cards/" + cardId;
						restTemplate.exchange(url8b, HttpMethod.DELETE, ghRequestEmptyBody, ArrayList.class);
					}
				});
			}
			//8c. Loop through issues (for 1 column) and add cards
			String url8c = "https://api.github.com/projects/columns/" + columnIds.get(desiredColumnNames.get(i)) + "/cards";
			issues.get(desiredColumnNames.get(i)).forEach((String title, String desc) -> {
				HttpEntity<String> request8c = new HttpEntity<>("{\"note\":\"" + desc + "\"}", gitHubHeaders);
				try {
					ResponseEntity<ArrayList> response8c = restTemplate.exchange(url8c, HttpMethod.POST, request8c, ArrayList.class);
				} catch(Exception e) {
				}
			});
			//8d. Obtain cards again, extract titles, and sort them
			cards = getCardsInColumn(columnIds.get(desiredColumnNames.get(i)),ghRequestEmptyBody);
			Object[] cardTitlesArr = cards.keySet().toArray();
			String[] cardTitlesSorted = new String[cardTitlesArr.length];
			for(int j = 0; j < cardTitlesArr.length; j++) {
				cardTitlesSorted[j] = (String) cardTitlesArr[j];
			}
			Arrays.sort(cardTitlesSorted,(String title1, String title2) -> {	//Custom sorting function, to take number
				Integer di1 = title1.indexOf('-')==-1?title1.length():title1.indexOf('-');	//at end of title into account
				Integer di2 = title2.indexOf('-')==-1?title2.length():title2.indexOf('-');
				String text1 = title1.substring(0,di1);
				String text2 = title2.substring(0,di2);
				if(text1.compareToIgnoreCase(text2)==0) {
					Integer n1 = di1 == -1 ? 0 : Integer.parseInt(title1.substring(di1 + 1));
					Integer n2 = di2 == -1 ? 0 : Integer.parseInt(title2.substring(di2 + 1));
					return n1.compareTo(n2);
				} else {
					return text1.compareToIgnoreCase(text2);
				}
			});
			Map<String, String> cardsOrder = new HashMap<>();
			cardsOrder.put(cardTitlesSorted[0],"top");
			for(int j = 1; j < cardTitlesSorted.length; j++) {
				Integer cardIdPrevious = cards.get(cardTitlesSorted[j-1]);
				cardsOrder.put(cardTitlesSorted[j],"after:" + cardIdPrevious);
			}
			//8e. Sort the cards
			for(int j = 0; j < cardTitlesSorted.length; j++) {
				String url8e = "https://api.github.com/projects/columns/cards/" + cards.get(cardTitlesSorted[j]) + "/moves";
				HttpEntity<String> request8e = new HttpEntity<>("{\"position\":\"" + cardsOrder.get(cardTitlesSorted[j]) + "\"}", gitHubHeaders);
				try{
					ResponseEntity<ArrayList> response8e = restTemplate.exchange(url8e, HttpMethod.POST, request8e, ArrayList.class);
				} catch(Exception e) {
				}
			}
		}
	}

	private Map<String, Map<String,String>> saveIssues(ArrayList<Map<String,Object>> jsonIssues, Map<String, Map<String,String>> issues) {
		jsonIssues.forEach((Map<String, Object> m) -> {
			Map<String, Object> jsonFields = (Map<String, Object>) m.get("fields");
			if(jsonFields.containsKey("parent")) {
				String jsonKey = (String) m.get("key");
				String jsonAssignee = jsonFields.get("assignee") == null ? "Unassigned" : (String) ((Map<String, Object>) jsonFields.get("assignee")).get("displayName");
				Map<String,Object> jsonParent = (Map<String,Object>) jsonFields.get("parent");
				String jsonParentKey = (String) jsonParent.get("key");
				String jsonParentSummary = ((Map<String,String>) jsonParent.get("fields")).get("summary");
				String desc = "<b>" + jsonKey + "</b>: " + jsonFields.get("summary") + "<p><i>" + jsonAssignee + "</i><ol>Child of <b>" +
						jsonParentKey + "</b>: " + jsonParentSummary + "<p></ol>";
				Map<String, Object> jsonStatus = (Map<String, Object>) jsonFields.get("status");
				String jsonStatusName = (String) jsonStatus.get("name");
				if(replaceColumns.containsKey(jsonStatusName)) {
					jsonStatusName = replaceColumns.get(jsonStatusName);
				}
				if (issues.containsKey(jsonStatusName)) {
					issues.get(jsonStatusName).put(jsonKey,desc);
				} else {
					Map<String,String> map = new HashMap<>();
					map.put(jsonKey,desc);
					issues.put(jsonStatusName, map);
				}
			}
		});
	return issues;
	}

	private Map<String, Integer> getCardsInColumn(Integer columnId, HttpEntity<String> ghRequestEmptyBody) {
		boolean finished = false;
		int pn = 1;
		Map<String,Integer> cards = new HashMap<>();
		while(!finished) {
			String url = "https://api.github.com/projects/columns/" + columnId + "/cards?per_page=100&page=" + pn;
			ResponseEntity<ArrayList> response = restTemplate.exchange(url, HttpMethod.GET, ghRequestEmptyBody, ArrayList.class);
			for (int j = 0; j < response.getBody().size(); j++) {        //Obtains card titles and ids
				Map<String, Object> card = (Map<String, Object>) response.getBody().get(j);
				Integer cardId = (Integer) card.get("id");
				String cardText = (String) card.get("note");
				int bi = cardText.indexOf("<b>")==-1?0:cardText.indexOf("<b>")+3;
				int ei = cardText.indexOf("</b>")==-1?0:cardText.indexOf("</b>");
				String issueTitle = cardText.substring(bi,ei).trim();
				cards.put(issueTitle, cardId);
			}
			if(response.getBody().size()!=100) {	//If the response had 100 entries, go to the next page, otherwise stop
				finished = true;
			} else {
				pn++;
			}
		}
		return cards;
	}
}
