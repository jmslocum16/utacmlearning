import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
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
	private static Set<String> problemNames = new HashSet<String>();
	private static int nextProblemNumber = 0;
	private static int nextFolderNumber = 0;
	
	private static Node root = null;
	
  public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("need 2 args, dropbox app key and dropbox app secret");
			return;
		}
		// init dropbox client
		try {
			initDbxClient(args[0], args[1]);
		} catch (Exception e) {
			System.err.println("Exception initializing dropbox stuff: " + e.toString());
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
		server.createContext("/input", new ViewInputHandler());
		server.createContext("/output", new OutputHandler());
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
			int responseCode = 200;
			StringBuilder response = new StringBuilder();
			StringBuilder top = new StringBuilder();
			StringBuilder bottom = new StringBuilder();
			BufferedReader topReader, bottomReader;
			try {
				topReader = new BufferedReader(new FileReader(new File("main-top.html")));
				bottomReader = new BufferedReader(new FileReader(new File("main-bottom.html")));
				while (topReader.ready()) {
					top.append(topReader.readLine().trim());
				}
				while (bottomReader.ready()) {
					bottom.append(bottomReader.readLine().trim());
				}
			} catch (IOException e) {
				System.err.println("Error serving main html files.");
				responseCode = 500;
				response.append("Error serving main html files.");
			}
			String fileTreeHtml = null;
			if (responseCode == 200) {
				// still ok, make file tree html
				fileTreeHtml = serveMainTree();
				if (fileTreeHtml == null) {
					System.err.println("Error serving main file tree.");
					responseCode =  500;
					response.append("Error serving main file tree.");
				}	
			}
			if (responseCode == 200) {
				//response.append("<section>");
				response.append(top.toString());
				//response.append("</section><section>");
				response.append(fileTreeHtml);
				//response.append("</section><section>");
				response.append(bottom.toString());
				//response.append("</section>");
			}
			t.sendResponseHeaders(responseCode, response.toString().getBytes().length);
			OutputStream os = t.getResponseBody();
			os.write(response.toString().getBytes());
			os.close();
		}
	}

	private static class Node {
		String name;
		int id;
		List<Node> children = null;
		String html = null;
		Node(String name, int id) {
			this.name = name;
			this.id = id;
		}
		void addChild(Node node) {
			if (children == null) {
				children = new ArrayList<Node>();
			}
			children.add(node);
			// invalidate html
			html = null;
		}
		boolean isLeaf() {
			return children == null;
		}
		// TODO make this expandable tree
		String getHtml(int indent) {
			if (html == null) {
				String indentStr = getIndent(indent);
				StringBuilder response = new StringBuilder();
				if (isLeaf()) {
					// link to problem
					response.append("<div>"+indentStr+"<a href=problem?"+id+">Problem: "+name+"</a></div>");
				} else {
					response.append("<div><div>"+indentStr+name+"</div>");
					if (overviewDirs.containsKey(id)) {
						response.append("<div>"+indentStr+"<a href=overview?"+id+">"+name+" overview</a></div>");
					}
					for (Node n : children) {
						String childHtml = n.getHtml(indent+1);
						if (childHtml == null) {
							return null;
						}
						response.append(childHtml);
					}
					response.append("</div>");
				}
				html = response.toString();
			}
			return html;
		}
		private String getIndent(int indent) {
			StringBuilder ret = new StringBuilder();
			for (int i = 0; i < 4 * indent; i++) {
				ret.append("&nbsp;");
			}
			return ret.toString();
		}
	}

	static class RefreshWorker implements Runnable {
		@Override
		public void run() {
			refreshImage();
		}
	}

	private static boolean refreshImage() {
		overviewDirs.clear();
		problemDirs.clear();
		problemNames.clear();
		nextProblemNumber = 0;
		nextFolderNumber = 0;
		Node newRoot = computeNode("/");
		if (newRoot == null) {
			System.err.println("image load failed");
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
			Node node = new Node(name, id);
			if (isValidProblem(listing)) {
				// do file stuff
				problemDirs.put(id, path);
				problemNames.add(name);
				nextProblemNumber = Math.max(nextProblemNumber, id + 1);
				System.out.println("added problem " + name);
			} else {
				// folder
				nextFolderNumber = Math.max(nextFolderNumber, id + 1);
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
			System.err.println("exception computing node path " + path);
			e.printStackTrace();
			return null;
		}
	}
	
	/**
	 * Serves main file tree html, returns null if error.
	 */
	private static String serveMainTree() {
		if (root == null || root.children == null) return null;
		StringBuilder response = new StringBuilder();
		for (Node n : root.children) {
			String childHtml = n.getHtml(0);
			if (childHtml == null) {
				return null;
			}
			response.append(childHtml);
		}
		return response.toString();
	}

	private static boolean isValidProblem(DbxEntry.WithChildren listing) {	
		if (!containsFile(listing, "description.txt")) return false;
		if (!containsFile(listing, "solved.txt")) return false;
		if (!containsFile(listing, "input.txt")) return false;
		if (!containsFile(listing, "output.txt")) return false;
		// added editorial
		if (!containsFile(listing, "editorial.txt")) return false;
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
			System.err.println("Exception reading " + path + "from dropbox: " + e.toString());
			throw e;
		} catch (IOException e) {
			System.err.println("Exception reading " + path + "from dropbox: " + e.toString());
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
			String response = null;
			int responseCode = 200;
			if ("GET".equals(requestMethod)) {
				response = handleCreateProblem(t);
			}
			if (response == null) {
				response = "Error serving request";
				responseCode = 404;
			}
			t.sendResponseHeaders(responseCode, response.getBytes().length);
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}

	static String handleCreateProblem(HttpExchange t) {
		if (t.getRequestURI().getQuery() == null) {
			StringBuilder response = new StringBuilder();
			BufferedReader br;
			try {
				br = new BufferedReader(new FileReader(new File("create-problem.html")));
				while (br.ready()) {
					response.append(br.readLine());
				}
			} catch (IOException e) {
				return null;
			}
			return response.toString();
		}

		Map<String, String> params = getParams(t.getRequestURI().getQuery());
   		for (String value: params.keySet()) {
			if (params.get(value).length() == 0) {
				return "cannot have empty " + value;
			}
		}
			System.out.println("got create problem!!!!!");
		// do things with it
		try {
			createProblem(params);
		} catch (DbxException e) {
			System.err.println(e);
			return null;
		} catch (IOException e) {
			System.err.println(e);
			return null;
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
			return e.getMessage();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		System.out.println("Finished Creating problem");
		return "Success!`";
	}

	private static Map<String, String> getParams(String query) {
		String[] paramsString = query.split("&");  
		Map<String, String> params = new HashMap<String, String>();  
    for (String p : paramsString) {
				int index = p.indexOf("=");
				String key = p.substring(0, index);
				String val = p.substring(index + 1);
				try {
					 val = URLDecoder.decode(val, charset);
				} catch (IOException e) {}
        params.put(key, val);  
    }
		return params;
	}

	static void createProblem(Map<String, String> params) throws IOException, DbxException {
		// make file text from post request
		
		String name = params.get("name");
		if (problemNames.contains(name)) {
			throw new IllegalArgumentException("problem named " + name + " already exists!");
		}
		String path = params.get("path");
		System.out.println("creating problem " + name + " at " + path);
		StringBuilder description = new StringBuilder();
		description.append("<p>");
		description.append(params.get("desc"));
		description.append("</p><p>Input:<br>");
		description.append(params.get("inputdesc"));
		description.append("</p><p>Output:<br>");
		description.append(params.get("outputdesc"));
		description.append("</p><p>Input Constraints:<br><pre>");
		description.append(params.get("constraints"));
		description.append("</pre></p><p>Sample Input:<br><pre>");
		description.append(params.get("sampleinput"));
		description.append("</pre></p><p>Sample output:<br><pre>");
		description.append(params.get("sampleoutput"));
		description.append("</pre></p>");

		String input = params.get("judgeinput");
		String output = params.get("judgeoutput");
		String editorial = params.get("editorial");


		// actually make files on dropbox	
		String fullPath = path + "/" + name;
	
		// create folder
		if (!isValidDBPath(path)) {
			createFolderAndInfo(path);
		}
		dbxClient.createFolder(fullPath);

		fullPath += "/";
		
		// make info file
		int id = nextProblemNumber++;
		String info = name + "\n" + id;
		writeFileToDropbox(info, fullPath + "info.txt");
		
		// write other files
		writeFileToDropbox(description.toString(), fullPath + "description.txt");
		writeFileToDropbox(editorial, fullPath + "editorial.txt");
		writeFileToDropbox(input, fullPath + "input.txt");
		writeFileToDropbox(output, fullPath + "output.txt");
		writeFileToDropbox("", fullPath + "solved.txt");

		// add it to the current image asynchronously
		//refreshImage();
		Thread t = new Thread(new RefreshWorker());
		t.start();
	}

	static boolean isValidDBPath(String path) {
		DbxEntry res = null;
		try {
			res = dbxClient.getMetadata(path);
		} catch (DbxException e) {
			return false;
		}
		return res != null;
	}
	
	static void createFolderAndInfo(String path) throws IOException, DbxException {
		String[] pathParts = path.split("/");
		StringBuilder curPath = new StringBuilder();
		for (int i = 0; i < pathParts.length; i++) {
			curPath.append("/");
			curPath.append(pathParts[i]);
			if (isValidDBPath(curPath.toString())) continue;
			dbxClient.createFolder(curPath.toString());
			String info = pathParts[i] + "\n" + nextFolderNumber++;
			writeFileToDropbox(info, curPath.toString() + "/info.txt");
		}
	}

	static class ViewProblemHandler implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {
			// apparently java 7 is not backwards compatible with this, should just use t.getQueryString() if ever need to upgrade.
			String query = t.getRequestURI().getQuery();
			int problemNum = -1;
 			int responseCode = 200;
			String response = null;
			try {
				problemNum = Integer.parseInt(query);
			} catch (NumberFormatException e) {}
			if (problemNum >= 0 && problemDirs.containsKey(problemNum)) {
				response = getProblemHeader(problemNum);
				String path = problemDirs.get(problemNum) + "/description.txt";
				response += serveFile(path, true);
			} else {
				response = query + " is not a valid problem number.";
        responseCode = 404;
			}
			t.sendResponseHeaders(responseCode, response.getBytes().length);
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}

	private static String getProblemHeader(int problemNum) {
		String file = serveLocalFile("problem-header.html", false);
		file = file.replaceAll("<problemnumber>", ""+problemNum);
		return file;
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
				String path = overviewDirs.get(overviewNum) + "/overview.txt";
				response = serveFile(path, true);
			} else {
				response = query + " is not a valid problem number.";
        responseCode = 404;
			}
			t.sendResponseHeaders(responseCode, response.getBytes().length);
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}
	
	static class ViewInputHandler implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {
			String query = t.getRequestURI().getQuery();
			int inputNum = -1;
 			int responseCode = 200;
			String response = null;
			try {
				inputNum = Integer.parseInt(query);
			} catch (NumberFormatException e) {
				response = query + " is not a valid problem number.";
				responseCode = 404;
			}
			if (inputNum >= 0 && problemDirs.containsKey(inputNum)) {
				String path = problemDirs.get(inputNum) + "/input.txt";
				response = getFormattedInput(serveFile(path, true));
				// TODO make this actually download a file, and not make them have to c/p the input into a test file.
			} else {
				response = query + " is not a valid problem number.";
        responseCode = 404;
			}
			t.sendResponseHeaders(responseCode, response.getBytes().length);
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}

	private static String getFormattedInput(String inputData) {
		StringBuilder response = new StringBuilder();
		response.append("<p>This is the input to the problem. You can copy-paste it into your program's standard input or into a test file from here.</p><div>Input:</div><span><pre style=\"background-color:#CCCCCC\">");
		response.append(inputData);
		response.append("</pre></span>");
		return response.toString();
	}
	
	private static String serveLocalFile(String path, boolean keepWS) {
		StringBuilder response = new StringBuilder();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(new File(path)));
			while (br.ready()) {
				String line = br.readLine();
				if (line == null) break;
				if (!keepWS) line = line.trim();
				response.append(line);
				if (keepWS) response.append("\n");
			}
		} catch (Exception e) {
			System.err.println("exception reading local file " + path);
			e.printStackTrace();
			response = new StringBuilder();
			response.append("error reading file.");
		} finally {
			if (br != null) {
				try { br.close();} catch (IOException e){}
			}
		}
		return response.toString().trim();
	}

	private static String serveFile(String path, boolean keepWS) {
    StringBuilder response = new StringBuilder();
		BufferedReader br = null;
    try {
      br = readFileFromDbx(path);
      while (br.ready()) {
				String line = br.readLine();
				if (line == null) break;
				if (!keepWS) line = line.trim();
        response.append(line);
				if (keepWS) response.append("\n");
      }
    } catch (Exception e) {
      System.err.println("dropbox exception reading from " + path);
			e.printStackTrace();
      response = new StringBuilder();
      response.append("error reading file.");
    } finally {
			if (br != null) {
				try {br.close();} catch (IOException e) {}
			}
		}
    return response.toString().trim();
	}

	private static String[] readRequestBody(InputStream req, boolean keepWS) {
		BufferedReader br = null;
		try {
			ArrayList<String> files = new ArrayList<String>(2);
			br = new BufferedReader(new InputStreamReader(req));
			StringBuilder sb = null;
			boolean inFile = false;
			while (br.ready()) {
				String line = br.readLine();
				if (line == null) break;
				if (!keepWS) line = line.trim();
				boolean newInFile = !(line.startsWith("------WebKitFormBoundary")
						|| line.startsWith("Content-Disposition:")
						|| line.startsWith("Content-Type:"));
				if (!inFile && newInFile) {
					sb = new StringBuilder();
				} else if (inFile && !newInFile) {
					files.add(sb.toString().trim());
				}
				if (newInFile) {
					sb.append(line);
					if (keepWS) sb.append("\n");
				}
				inFile = newInFile;
			}
			String[] ret = new String[files.size()];
			for (int i = 0; i < files.size(); i++) {
				ret[i] = files.get(i);
			}
			return ret;
		} catch (Exception e) {
			System.err.println("error reading request body");
			e.printStackTrace();
			return null;
		} finally {
			if (br != null) {
				try {br.close();} catch (IOException e) {}
			}
		}
	}
	
	static class OutputHandler implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {
			// TODO handle big/small l8r
 			int responseCode = 200;
			String response = null;
			String query = t.getRequestURI().getQuery();

			int outputNum = -1;
			try {
				outputNum = Integer.parseInt(query);
			} catch (NumberFormatException e) {
				response = query + " is not a valid problem number.";
				responseCode = 404;
			}
			if (outputNum >= 0 && problemDirs.containsKey(outputNum)) {
				System.out.println("handling output " + t.getRequestMethod() + "for problem number " + outputNum);
				if ("GET".equals(t.getRequestMethod())) {
					response = handleOutputGET(outputNum);
				} else if ("POST".equals(t.getRequestMethod())) {
					response = handleOutputPOST(outputNum, t);
				} else {
					response = "Invalid request method " + t.getRequestMethod();
					responseCode = 404;
				}
			} else {
				response = query + " is not a valid problem number.";
        responseCode = 404;
			}
			t.sendResponseHeaders(responseCode, response.getBytes().length);
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}

	private static String handleOutputGET(int problemNumber) {
		String file = serveLocalFile("output-page.html", false);
		file = file.replaceAll("<problemnumber>", ""+problemNumber);
		System.out.println("returning output page:");
		System.out.println(file);
		return file;
	}

	private static String handleOutputPOST(int problemNumber, HttpExchange t) {
		// TODO fix
		String[] body = readRequestBody(t.getRequestBody(), true);
		if (body != null) {
			if (body.length < 2) {
				System.err.println("incorrect submission...");
				return "wat";
			}
			String programOutput = null;
			System.out.println("text submission:");
			System.out.println(body[0]);
			System.out.println("file submission: ");
			System.out.println(body[1]);
			if (body[0].length() > 0 && body[1].length() > 0) {
				System.err.println("both text and file specified.");
				return "You cannot both upload a file and paste output!";
			} else if (body[0].length() > 0) {
				programOutput = body[0];
			} else if (body[1].length() > 0) {
				programOutput = body[1];
			} else {
				return "You didn't submit anything.";
			}
			String path = problemDirs.get(problemNumber) + "/output.txt";
			return getFormattedOutput(serveFile(path, true), programOutput);
		} else {
			return "Error reading output.";
		}
	}	
	
	private static String getFormattedOutput(String expected, String given) {
		System.out.println("given output: ");
		System.out.println(given);
		// TODO header or anything?
		String[] expectedLines = expected.split("\n");
		String[] givenLines = given.split("\n");
		if (expectedLines.length != givenLines.length) {
			return "<div style=\"background-color:red\"><p>Incorrect submission. Your output has " + givenLines.length + " lines, and the expected output has " + expectedLines.length + " lines.</p></div>";
		}
		boolean same = true;
		boolean[] lineSame = new boolean[expectedLines.length];
		for (int i = 0; i < expectedLines.length; i++) {
			lineSame[i] = true;
			String[] expectedLine = expectedLines[i].split("\\s+");
			String[] givenLine = givenLines[i].split("\\s+");
			if (givenLine.length != expectedLine.length) {
				lineSame[i] = false;
				same = false;
			}
			for (int j = 0; j < expectedLine.length; j++) {
				if (!expectedLine[j].equals(givenLine[j])) {
					same = false;
					lineSame[i] = false;
				}
			}
		}
		if (same) {
			return "<div style=\"background-color:green\"><p>Correct submission! Your output matched the expected output!</p></div>";
			// TODO handleSolved();
		} else {
			StringBuilder response = new StringBuilder();
			response.append("<h1>Incorrect response.</h1>");
			response.append("<div>");
			response.append("<div style=\"display:inline-block;border-style:solid;border-width:thin\">");	
			response.append("<div><b>Expected:</b></div>");
			for (int i = 0; i < expectedLines.length; i++) {
				response.append("<div style=\"background-color:");
				response.append(lineSame[i]?"green":"red");
				response.append("\">");
				response.append(expectedLines[i]);
				response.append("</div>");
			}
			response.append("</div>");
			response.append("<div style=\"display:inline-block;border-style:solid;border-width:thin\">");	
			response.append("<div><b>Yours:</b></div>");
			for (int i = 0; i < givenLines.length; i++) {
				response.append("<div style=\"background-color:");
				response.append(lineSame[i]?"green":"red");
				response.append("\">");
				response.append(givenLines[i]);
				response.append("</div>");
			}
			response.append("</div></div>");
			return response.toString();
		}
	}
}
