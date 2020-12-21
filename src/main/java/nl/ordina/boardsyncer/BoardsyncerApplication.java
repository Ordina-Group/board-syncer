package nl.ordina.boardsyncer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	@Bean
	public void syncBoards() {
		String url1 = "https://jobcrawler.atlassian.net/rest/api/latest/search?maxResults=";
		HttpHeaders headers1 = new HttpHeaders();
		headers1.add("Authorization", "Basic " + jiraToken);
		headers1.add("content-type", "application/json");
		HttpEntity<String> request1 = new HttpEntity<>("", headers1);
//        restTemplate.getMessageConverters().add(new FormHttpMessageConverter());
		ResponseEntity<Map> response1 = restTemplate.exchange(url1 + "1000", HttpMethod.GET, request1, Map.class);
/*        Map<String,Object> jsonIssues = (Map<String,Object>) response1.getBody().get("issues");
        Map<String,String> issues = new HashMap<>();*/
/*        jsonIssues.forEach((String s, Object o) -> {if()

                                                    String key = (String) jsonIssues.get("key");
                                                    Map<String,Object> fields = (Map<String,Object>) jsonIssues.get("fields");
                                                    String text = "<b>" + key + "</b>\r\n<i>" + fields.get("assignee") + "</i>\r\n" + fields.get("summary");
                                                    issues.put(key, text);});*/

		Integer totalResults = (Integer) response1.getBody().get("total");
		Integer resultsPerPage = (Integer) response1.getBody().get("maxResults");
		int nrRequests = (int) Math.ceil(totalResults / resultsPerPage);
		for (int i = 1; i < nrRequests; i++) {
			ResponseEntity<Map> response = restTemplate.exchange(url1 + resultsPerPage, HttpMethod.GET, request1, Map.class);
		}


/*      Requests done to GitHub (with option to delete superfluous):
        2: Find project id
        3: Find column ID's
        4 (o): Delete unnecessary columns
        5: Add columns if necessary
        6: Put the columns in order
        7: Per column:
            a: Find what cards are there
            b (o): For each card with a name that doesn't belong there, delete it.
            c: Update/add cards
            d: Sort cards
*/

		//2. Find Project id
		String url2 = "https://api.github.com/orgs/OrdinaNederland/projects";
		HttpHeaders headers2 = new HttpHeaders();
		headers2.add("Authorization", "token " + gitHubToken);
		headers2.add("Accept", "application/vnd.github.inertia-preview+json");
		HttpEntity<String> request2 = new HttpEntity<>("", headers2);
		ResponseEntity<List> response2 = restTemplate.exchange(url2, HttpMethod.GET, request2, List.class);

		Integer projectId = 0;
		for (int i = 0; i < response2.getBody().size(); i++) {
			Map<String, Object> project = (Map<String, Object>) response2.getBody().get(i);
			if (project.get("name").equals("Jobcrawler")) {
				projectId = (Integer) project.get("id");
			}
		}

		//3. Finding column names
		String url3 = "https://api.github.com/projects/" + projectId + "/columns";
		HttpHeaders headers3 = new HttpHeaders();
		headers3.add("Authorization", "token " + gitHubToken);
		headers3.add("Accept", "application/vnd.github.inertia-preview+json");
		HttpEntity<String> request3 = new HttpEntity<>("", headers3);
		ResponseEntity<List> response3 = restTemplate.exchange(url3, HttpMethod.GET, request3, List.class);

		Map<String, Integer> columnIds = new HashMap<>();
		for (int i = 0; i < response3.getBody().size(); i++) {
			Map<String, Object> column = (Map<String, Object>) response3.getBody().get(i);
			columnIds.put((String) column.get("name"), (Integer) column.get("id"));
		}

		//4. Later, add option here to delete extra columns
		//     columnNames.forEach((String s) -> {if(s.equals(column.get("name"))) columnIds.put(s,(Integer) column.get("id"));});

		//5. Add columns that aren't there yet
		String[] desiredColumnNames = {"Backlog", "To do", "Doing", "Review", "Done"};
		for (String s : desiredColumnNames) {
			if (!columnIds.containsKey(s)) {
				String url5 = "https://api.github.com/projects/" + projectId + "/columns";
				HttpHeaders headers5 = new HttpHeaders();
				headers5.add("Authorization", "token " + gitHubToken);
				headers5.add("Accept", "application/vnd.github.inertia-preview+json");
				String body5 = "{\"name\": \"" + s + "\"}";
				HttpEntity<String> request5 = new HttpEntity<>(body5, headers5);
				ResponseEntity<Map> response5 = restTemplate.exchange(url5, HttpMethod.POST, request5, Map.class);
				columnIds.put(s, (Integer) response5.getBody().get("id"));
			}
		}

		//6. Move columns to the right order
		for (int i = 1; i < desiredColumnNames.length; i++) {
			String url6 = "https://api.github.com/projects/columns/" + columnIds.get(desiredColumnNames[i]) + "/moves";
			HttpHeaders headers6 = new HttpHeaders();
			headers6.add("Authorization", "token " + gitHubToken);
			headers6.add("Accept", "application/vnd.github.inertia-preview+json");
			String body6 = "{\"position\": \"after:" + columnIds.get(desiredColumnNames[i - 1]) + "\"}";
			HttpEntity<String> request6 = new HttpEntity<>(body6, headers6);
			ResponseEntity<Map> response6 = restTemplate.exchange(url6, HttpMethod.POST, request6, Map.class);
		}

		//7a. Per column, obtain cards. Id and text
		Map<String, Map<Integer, String>> cards = new HashMap<>();
		for (int i = 0; i < desiredColumnNames.length; i++) {
			Map<Integer, String> cards4oneColumn = new HashMap<>();
			String url7a = "https://api.github.com/projects/columns/" + columnIds.get(desiredColumnNames[i]) + "/cards";
			HttpHeaders headers7a = new HttpHeaders();
			headers7a.add("Authorization", "token " + gitHubToken);
			headers7a.add("Accept", "application/vnd.github.inertia-preview+json");
			HttpEntity<String> request7a = new HttpEntity<>("", headers7a);
			ResponseEntity<ArrayList> response7a = restTemplate.exchange(url7a, HttpMethod.GET, request7a, ArrayList.class);
			for (int j = 0; j < response7a.getBody().size(); j++) {
				Map<String, Object> card = (Map<String, Object>) response7a.getBody().get(j);
				cards4oneColumn.put((Integer) card.get("id"), (String) card.get("note"));
			}

			//7b. Delete superfluous cards

			//7c. Update/add cards  (if not possible to add at specific locaiton, sorting needs to be moved to after

			//7d. Sort cards

		}

		System.out.println(projectId);
		System.out.println("Finished");

	}
}
