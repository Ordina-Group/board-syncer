package nl.ordina.boardsyncer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@SpringBootApplication
public class BoardsyncerApplication {

	static RestTemplate restTemplate = new RestTemplate();
	static boolean deleteExtras = true;
	@Value("${spring.boardsync.jira}")
	private String jiraToken;
	@Value("${spring.boardsync.github}")
	private String gitHubToken;

	public static void main(String[] args) {

		SpringApplication.run(BoardsyncerApplication.class, args);
	}

/*		Requests performed by syncBoards:
			Request to Jira:
			1: Retrieve issues
			Requests to GitHub (o are optional, to delete superfluous):
			2: Find project id
			3: Find column ID's
			4 (o): Delete unnecessary columns
			5: Add columns if necessary
			6: Put the columns in order
			7: Per column:
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

		String url1 = "https://jobcrawler.atlassian.net/rest/api/latest/search?maxResults=";
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
		String url2 = "https://api.github.com/orgs/OrdinaNederland/projects";
		HttpHeaders gitHubHeaders = new HttpHeaders();
		gitHubHeaders.add("Authorization", "token " + gitHubToken);
		gitHubHeaders.add("Accept", "application/vnd.github.inertia-preview+json");
		HttpEntity<String> ghRequestEmptyBody = new HttpEntity<>("", gitHubHeaders);
		ResponseEntity<List> response2 = restTemplate.exchange(url2, HttpMethod.GET, ghRequestEmptyBody, List.class);

		Integer projectId = 0;
		for (int i = 0; i < response2.getBody().size(); i++) {
			Map<String, Object> project = (Map<String, Object>) response2.getBody().get(i);
			if (project.get("name").equals("Jobcrawler")) {
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

		//4. Delete superfluous columns
		if(deleteExtras) {
			columnIds.forEach((String colName, Integer colId) -> {
					if (!desiredColumnNames.contains(colName)) {
						String url4 = "https://api.github.com/projects/columns/" + colId;
						ResponseEntity<List> response4 = restTemplate.exchange(url4, HttpMethod.DELETE, ghRequestEmptyBody, List.class);
					}
				}
			);
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

		//6. Move columns to the right order
		for (int i = 1; i < desiredColumnNames.size(); i++) {
			String url6 = "https://api.github.com/projects/columns/" + columnIds.get(desiredColumnNames.get(i)) + "/moves";
			String body6 = "{\"position\": \"after:" + columnIds.get(desiredColumnNames.get(i - 1)) + "\"}";
			HttpEntity<String> request6 = new HttpEntity<>(body6, gitHubHeaders);
			ResponseEntity<Map> response6 = restTemplate.exchange(url6, HttpMethod.POST, request6, Map.class);
		}

		//7a. Per column, obtain cards. Id and text
		Map<String, Map<Integer, String>> cards = new HashMap<>();
		for (int i = 0; i < desiredColumnNames.size(); i++) {    //Loops through columns to get cards
			String url7a = "https://api.github.com/projects/columns/" + columnIds.get(desiredColumnNames.get(i)) + "/cards";
			ResponseEntity<ArrayList> response7a = restTemplate.exchange(url7a, HttpMethod.GET, ghRequestEmptyBody, ArrayList.class);
			for (int j = 0; j < response7a.getBody().size(); j++) {        //Loops through cards, deletes ones with same name as issues, and perhaps deletes superfluous ones
				Map<String, Object> card = (Map<String, Object>) response7a.getBody().get(j);
				Integer cardId = (Integer) card.get("id");
				String cardText = (String) card.get("note");
				int bi = Math.max(cardText.indexOf("<u>"),0);
				int ei = Math.max(cardText.indexOf("</u>"),0);
				String issueTitle = cardText.substring(bi,ei).trim();
				//7b. Delete cards if they're issues from Jira (because they're added again later) or superfluous (optional)
				if ((issues.get(desiredColumnNames.get(i)).containsKey(issueTitle)) || deleteExtras && !issues.get(desiredColumnNames.get(i)).containsKey(issueTitle)) {
					String url7b = "https://api.github.com/projects/columns/cards/" + cardId;
					ResponseEntity<ArrayList> response7b = restTemplate.exchange(url7b, HttpMethod.DELETE, ghRequestEmptyBody, ArrayList.class);
				}
			}
			//7c. Loop through issues (for 1 column) and add cards
			String url7c = "https://api.github.com/projects/columns/" + columnIds.get(desiredColumnNames.get(i)) + "/cards";
			issues.get(desiredColumnNames.get(i)).forEach((String title, String desc) -> {
				HttpEntity<String> request7c = new HttpEntity<>("{\"note\":\"" + desc + "\"}", gitHubHeaders);
				try {
					ResponseEntity<ArrayList> response7c = restTemplate.exchange(url7c, HttpMethod.POST, request7c, ArrayList.class);
				} catch(Exception e) {
				}
			});

			//7d. Obtain cards again
			String url7d = "https://api.github.com/projects/columns/" + columnIds.get(desiredColumnNames.get(i)) + "/cards";
			ResponseEntity<ArrayList> response7d = restTemplate.exchange(url7d, HttpMethod.GET, ghRequestEmptyBody, ArrayList.class);
			Map<String, Integer> cardTitles = new HashMap<>(); // card ID, card title, card order
			for (int j = 0; j < response7d.getBody().size(); j++) {        //Loops through cards, sorts them
				Map<String, Object> card = (Map<String, Object>) response7d.getBody().get(j);
				Integer cardId = (Integer) card.get("id");
				String cardText = (String) card.get("note");
				int bi = cardText.indexOf("<b>")==-1?0:cardText.indexOf("<b>")+3;
				int ei = cardText.indexOf("</b>")==-1?0:cardText.indexOf("</b>");
				String issueTitle = cardText.substring(bi,ei).trim();
				cardTitles.put(issueTitle, cardId);
			}
			Object[] cardTitlesArr = cardTitles.keySet().toArray();
			String[] cardTitlesSorted = new String[cardTitlesArr.length];
			for(int j = 0; j < cardTitlesArr.length; j++) {
				cardTitlesSorted[j] = (String) cardTitlesArr[j];
			}
			Arrays.sort(cardTitlesSorted);
			Map<String, String> cardsOrder = new HashMap<>();
			cardsOrder.put(cardTitlesSorted[0],"top");
			for(int j = 1; j < cardTitlesSorted.length; j++) {
				Integer cardIdPrevious = cardTitles.get(cardTitlesSorted[j-1]);
				cardsOrder.put(cardTitlesSorted[j],"after:" + cardIdPrevious);
			}
			for(int j = 0; j < cardTitlesSorted.length; j++) {
				String url7e = "https://api.github.com/projects/columns/cards/" + cardTitles.get(cardTitlesSorted[j]) + "/moves";
				HttpEntity<String> request7e = new HttpEntity<>("{\"position\":\"" + cardsOrder.get(cardTitlesSorted[j]) + "\"}", gitHubHeaders);
				try{	//7e. Sort cards
					ResponseEntity<ArrayList> response7e = restTemplate.exchange(url7e, HttpMethod.POST, request7e, ArrayList.class);
				} catch(Exception e) {
				}
			}
		}
	}


	private Map<String, Map<String,String>> saveIssues(ArrayList<Map<String,Object>> jsonIssues, Map<String, Map<String,String>> issues) {
		Map<String,String> replaceColumns = new HashMap<>();
		replaceColumns.put("In Progress","Doing");
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

}
