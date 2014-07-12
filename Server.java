import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Locale;

import com.dropbox.core.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Server {
	static String DB_APP_KEY;
	static String DB_APP_SECRET;
	
	private static DbxClient dbxClient;
	
  public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.out.println("need 2 args, dropbox app key and dropbox app secret");
			return;
		}
		// init dropbox stuff
		try {
			initDbx(args[0], args[1]);
		} catch (Exception e) {
			System.out.println("Exception initializing dropbox stuff: " + e.toString());
			return;
		}

		HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
		server.createContext("/", new DefaultHandler());
		server.createContext("/newproblem", new CreateProblemHandler());
		server.createContext("/problem", new ViewProblemHandler());
		server.setExecutor(null); // creates a default executor
		server.start();
	}

	private static void initDbx(String key, String secret) throws IOException,DbxException {
		DB_APP_KEY = key;
		DB_APP_SECRET = secret;
		DbxAppInfo appInfo = new DbxAppInfo(key, secret);
		DbxRequestConfig config = new DbxRequestConfig("UT ACM Training",
				Locale.getDefault().toString());
		DbxWebAuthNoRedirect webAuth = new DbxWebAuthNoRedirect(config, appInfo);

		// Have the user sign in and authorize your app.
		String authorizeUrl = webAuth.start();
		System.out.println("1. Go to: " + authorizeUrl);
		System.out.println("2. Click \"Allow\" (you might have to log in first)");
		System.out.println("3. Copy the authorization code.");
		System.out.print("4. Enter the authorization code: ");
		String code = new BufferedReader(new InputStreamReader(System.in)).readLine().trim();

		// This will fail if the user enters an invalid authorization code.
		DbxAuthFinish authFinish = webAuth.finish(code);
		String accessToken = authFinish.accessToken;
    dbxClient = new DbxClient(config, accessToken);
		System.out.println("successfully initialized dropbox client!");
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
		t.sendResponseHeaders(200, response.length());
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
