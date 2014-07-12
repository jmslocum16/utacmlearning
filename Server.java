import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.dropbox.core.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Server {
	// Dropbox stuff
	private static String DB_APP_KEY;
	private static String DB_APP_SECRET;
	private static DbxClient dbxClient;
	
	private static String charset = "UTF-8";

	// to synchronize stuff on. These are super cool.
	private static final ReadWriteLock lock = new ReentrantReadWriteLock();
	// image
	private static Map<Integer, String> overviewDirs = new HashMap<Integer, String>();
	private static Map<Integer, String> problemDirs = new HashMap<Integer, String>();
	
	private static Node root = null;
	
  public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.out.println("need 2 args, dropbox app key and dropbox app secret");
			return;
		}
		// init dropbox client
		try {
			initDbxClient(args[0], args[1]);
		} catch (Exception e) {
			System.out.println("Exception initializing dropbox stuff: " + e.toString());
			return;
		}

		// read state of application from dropbox
		refreshImage();

		// start server
		HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
		server.createContext("/", new DefaultHandler());
		server.createContext("/newproblem", new CreateProblemHandler());
		server.createContext("/problem", new ViewProblemHandler());
		server.createContext("/overview", new ViewOverviewHandler());
		server.setExecutor(null); // creates a default executor
		server.start();
	}

	private static void initDbxClient(String key, String secret) throws IOException,DbxException {
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

	private static class Node {
		String name;
		List<Node> children;
		Node(String name) {
			this.name = name;
		}
		void addChild(Node node) {
			if (children == null) {
				children = new ArrayList<Node>();
			}
			children.add(node);
		}
		boolean isLeaf() {
			return children == null;
		}
	}

	private static boolean refreshImage() {
		overviewDirs.clear();
		problemDirs.clear();
		Node newRoot = computeNode("/");
		if (newRoot == null) {
			System.out.println("image load failed");
			return false;
		}
		lock.writeLock().lock();
		root = newRoot;
		lock.writeLock().unlock();
		printTree();
		System.out.println("problems: ");
		for (int i : problemDirs.keySet()) {
			System.out.println(problemDirs.get(i) + ": " + i);
		}
		System.out.println("overviews: ");
		for (int i : overviewDirs.keySet()) {
			System.out.println(overviewDirs.get(i) + ": " + i);
		}
		return true;
	}

	// returns null if DNE or doesn't have proper data
	private static Node computeNode(String path) {
		System.out.println("computing node " + path);
		try {
			// get metadata for this path
			DbxEntry.WithChildren listing = dbxClient.getMetadataWithChildren(path);
			DbxEntry cur = listing.entry;
			if (!cur.isFolder()) {
				System.out.println(path + " isn't a directory, exiting.");
				return null; // problems are directories
			}
			
			// they all must have an info.txt file
			BufferedReader br = readFileFromDbx(path+"/info.txt");
			if (br == null) {
				System.out.println(path + " has no info.txt, exiting");
				return null;
			}
			String name = br.readLine().trim();
			int id = Integer.parseInt(br.readLine().trim());
			Node node = new Node(name);
			if (isValidProblem(listing)) {
				// do file stuff
				problemDirs.put(id, path);
				System.out.println("added problem " + name);
			} else {
				// folder
				// see if has overview
				boolean isOverview = containsFile(listing, "overview.txt");
				if (isOverview) {
					System.out.println("adding overview for " + name);
					overviewDirs.put(id, path);
				}
				// try to make all children
				for (DbxEntry child: listing.children) {
					Node childNode = computeNode(child.path);
					if (childNode != null) {
						System.out.println("adding child " + childNode.name + " for " + name);
						node.addChild(childNode);
					}
				}
				// if 0 children, this is useless node
				if (node.isLeaf() && !isOverview) {
					System.out.println(name + " is not a useful folder, pruning");
					return null;
				}
				System.out.println("created directory for " + name);
			}
			return node;
		} catch (Exception e) {
			System.out.println("exception computing node path " + path);
			e.printStackTrace();
			return null;
		}
	}

	private static boolean isValidProblem(DbxEntry.WithChildren listing) {	
		if (!containsFile(listing, "description.txt")) return false;
		if (!containsFile(listing, "solved.txt")) return false;
		if (!containsFile(listing, "input.txt")) return false;
		if (!containsFile(listing, "output.txt")) return false;
		return true;
	}

	private static boolean containsFile(DbxEntry.WithChildren listing, String fileName) {
		for (DbxEntry e: listing.children) {
			if (e.name.equals(fileName)) {
				return true;
			}
		}
		return false;
	}

	private static BufferedReader readFileFromDbx(String path) throws IOException, DbxException {
		// read file from dropbox into byte output stream.
		ByteArrayOutputStream out = null;
		try {
			out = new ByteArrayOutputStream();
			dbxClient.getFile(path, null, out);
		} catch (DbxException e) {
			System.out.println("Exception reading " + path + "from dropbox: " + e.toString());
			throw e;
		} catch (IOException e) {
			System.out.println("Exception reading " + path + "from dropbox: " + e.toString());
			throw e;
		} finally {
			if (out != null) {
				out.close();
			}	
		}
		return new BufferedReader(new StringReader(out.toString(charset)));
	}

	private static void writeFileToDropbox(String fileData, String path) throws DbxException, IOException {
		byte[] data = fileData.getBytes(charset);
		InputStream in = null;
		try {
			in = new ByteArrayInputStream(data);
			dbxClient.uploadFile(path, DbxWriteMode.add(), data.length, in);
		} catch (DbxException e) {
			System.err.println("Exception writing " + path + " to dropbox: " + e.toString());
			throw e;
		} catch (IOException e) {
			System.err.println("Exception writing " + path + " to dropbox: " + e.toString());
			throw e;
		}
	}

	private static void printTree() {
		printTree(root, 0);
	}
	
	private static void printTree(Node node, int tabs) {
		for (int i = 0; i < tabs; i++) {
			System.out.print("  ");
		}
		System.out.println(node.name);
		if (node.isLeaf()) return;
		for (Node n: node.children) {
			printTree(n, tabs + 1);
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
			} catch (NumberFormatException e) {}
			if (problemNum >= 0 && problemDirs.containsKey(problemNum)) {
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
		String path = problemDirs.get(problemNumber) + "/description.txt";
		StringBuilder response = new StringBuilder();
		try {
			BufferedReader br = readFileFromDbx(path);
			while (br.ready()) {
				String line = br.readLine();
				if (line == null) break;
				response.append(line.trim());
			}
		} catch (Exception e) {
			System.err.println("dropbox exception reading from " + path);
			e.printStackTrace();
			response = new StringBuilder();
			response.append("error reading problem file.");
		}
		return response.toString();
	}

	static class ViewOverviewHandler implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {
			String query = t.getRequestURI().getQuery();
			int overviewNum = -1;
 			int responseCode = 200;
			String response = null;
			try {
				overviewNum = Integer.parseInt(query);
			} catch (NumberFormatException e) {
				response = query + " is not a valid problem number.";
				responseCode = 404;
			}
			if (overviewNum >= 0 && overviewDirs.containsKey(overviewNum)) {
				response = serveOverview(overviewNum);
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
	
	private static String serveOverview(int overviewNumber) {	
		String path = overviewDirs.get(overviewNumber) + "/overview.txt";
		StringBuilder response = new StringBuilder();
		try {
			BufferedReader br = readFileFromDbx(path);
			while (br.ready()) {
				response.append(br.readLine().trim());
			}
		} catch (Exception e) {
			System.err.println("dropbox exception reading from " + path);
			response = new StringBuilder();
			response.append("error reading file.");
		}
		return response.toString();
	}
}
