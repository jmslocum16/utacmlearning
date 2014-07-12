import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Server {
  public static void main(String[] args) throws Exception {
		// TODO read in any and all keys so don't put them on a public github.
		HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
		server.createContext("/", new DefaultHandler());
		server.createContext("/newproblem", new CreateProblemHandler());
		server.createContext("/problem", new ViewProblemHandler());
		server.setExecutor(null); // creates a default executor
		server.start();
	}

	static class DefaultHandler implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {
			String response = "<div>Why can't I hold all these problems??!?</div>";
			t.sendResponseHeaders(200, response.length());
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}

	static class CreateProblemHandler implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {
			String requestMethod = t.getRequestMethod();
			if ("GET".equals(requestMethod)) {
				handleCreateProblemGET(t);
			} else if ("POST".equals(requestMethod)) {
				handleCreateProblemPOST(t);
			} else {
				String response = "n00pe";
				t.sendResponseHeaders(404, response.length());
				OutputStream os = t.getResponseBody();
				os.write(response.getBytes());
				os.close();
			}
		}
	}

	static void handleCreateProblemGET(HttpExchange t) throws IOException {
		String response = "<div>so you wanna make a problem eh?</div>";
		t.sendResponseHeaders(200, sb.length());
		OutputStream os = t.getResponseBody();
		os.write(response.getBytes());
		os.close();
	}

	static void handleCreateProblemPOST(HttpExchange t) throws IOException {
		// TODO
	}

	static class ViewProblemHandler implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {
			String query = t.getRequestURI().getQuery();
			int problemNum = -1;
 			int responseCode = 200;
			String response = null;
			try {
				problemNum = Integer.parseInt(query);
			} catch (NumberFormatException e) {
				response = query + " is not a valid problem number.";
				responseCode = 404;
			}
			if (problemNum >= 0) {
				response = serveProblem(problemNum);
			} else {
				response = query + " is not a valid problem number.";
        responseCode = 404;
			}
			t.sendResponseHeaders(responseCode, response.length());
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}

	private static String serveProblem(int problemNumber) {
		String response = "<div>Look at dis problem #" + problemNumber + "</div>";
		return response;
	}
}
