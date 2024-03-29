package topicus.results;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

import au.com.bytecode.opencsv.CSVReader;

import topicus.ConsoleScript;
import topicus.benchmarking.BenchmarksScript;

public class ProcessResultsScript extends ConsoleScript {
	class InvalidDataDirectoryException extends Exception {}
	class InvalidTenantIdException extends Exception {}
	class CancelledException extends Exception {}
	
	protected String resultsDirectory;
	
	protected Connection conn;
	
	protected boolean doOverwrite = false;
	
	protected int[] instanceBenchmarks = new int[100];
	
	protected String alias = null;
	
	public void run () throws Exception {
		printLine("Started-up results processing tool");	
				
		this._setOptions();
		
		if (!this.cliArgs.hasOption("start")) {
			if (!confirmBoolean("Start processing results? (y/n)")) {
				printError("Stopping");
				conn.close();
				throw new CancelledException();
			}
		}
		
		File resDir = new File(this.resultsDirectory);
		this.printLine("Found " + resDir.listFiles().length + " files in directory");
		
		Pattern pInstance = Pattern.compile("^benchmark-node(\\d+)-(.*).dat$");
		Pattern pBenchmark = Pattern.compile("^benchmark-(.*)-(\\d+)-nodes-(\\d+)-tenants-(\\d+)-users-(\\d+)-iterations(\\.csv)?$");
		Pattern pLoad = Pattern.compile("^load-(.*)-(\\d+)-nodes-tenant-(\\d+)(\\.csv)?$");
		
		File[] files = resDir.listFiles();
		
		printLine("Scanning directory for instance benchmark files");
		int instanceCount = 0;
		for (File file : files) {
			String fileName = file.getName();
			Matcher mInstance = pInstance.matcher(fileName);
			if (mInstance.find()) {
				this._parseInstanceFile(
					file,
					Integer.parseInt(mInstance.group(1)),
					mInstance.group(2)
				);				
				instanceCount++;
			}		
		}
		printLine("Found & parsed " + instanceCount + " instance benchmark files");
		
		for(File file : files) {
			String fileName = file.getName();
			
			Matcher mBenchmark = pBenchmark.matcher(fileName);			
			Matcher mLoad = pLoad.matcher(fileName);
			if (mBenchmark.find()) {			
				this._parseBenchmarkFile(
					file, 
					mBenchmark.group(1),
					Integer.parseInt(mBenchmark.group(2)),
					Integer.parseInt(mBenchmark.group(3)), 
					Integer.parseInt(mBenchmark.group(4)),
					Integer.parseInt(mBenchmark.group(5))
				);
			} else if (mLoad.find()) {
				this._parseLoadFile(
					file,
					mLoad.group(1),
					Integer.parseInt(mLoad.group(2)),
					Integer.parseInt(mLoad.group(3))
				);				
			} else {
				this.printLine("Found unknown file `" + fileName + "`, skipping!");
			}

		}
			
		this.printLine("Successfully finished!");
		conn.close();
	}
	
	protected void _parseInstanceFile(File file, int nodeId, String dateTime) throws IOException, SQLException {
		printLine("Found instance benchmarks for node " + nodeId + " on " + dateTime);
		
		// fix date time
		String[] split = dateTime.split("\\.");
		if (split.length != 5) {
			printError("Invalid date/time value: " + dateTime);
			return;
		}
		
		dateTime = split[2] + "-" + split[1] + "-" + split[0] + " " + split[3] + ":" + split[4];
				
		// parse file
		String data = FileUtils.readFileToString(file);
		
		Pattern pattern = Pattern.compile("===(.*?)-(\\d+)===(.*?)======", Pattern.DOTALL);
		Matcher matcher = pattern.matcher(data);
		
		Pattern timePattern = Pattern.compile("total time:(.*)s$", Pattern.MULTILINE);
		
		HashMap<String, List<Float>> times = new HashMap<String, List<Float>>();
		times.put("cpu",  new ArrayList<Float>());
		times.put("memory",  new ArrayList<Float>());
		times.put("file",  new ArrayList<Float>());
		times.put("oltp",  new ArrayList<Float>());
	
		while (matcher.find()) {
			String type = matcher.group(1).toLowerCase();
			String test = matcher.group(3).toLowerCase();
			
			Matcher mTime = timePattern.matcher(test);

			if (mTime.find()) {
				float time = Float.parseFloat(mTime.group(1));
				times.get(type).add(time);
			}			
		}
		
		// calculate averages
		HashMap<String, Double> averages = new HashMap<String, Double>();
		for (String key : times.keySet()) {
			float timeTotal = 0;
			for (float time : times.get(key)) {
				timeTotal += time;				
			}
			
			double avg = timeTotal / times.get(key).size();
			averages.put(key,  avg);
		}
				
		
		PreparedStatement q;
		ResultSet result;
		
		// check if this file already has results stored
		q = conn.prepareStatement("SELECT * FROM `instance` " +
				"WHERE instance_node = ? AND instance_datetime = ?");
		q.setInt(1,  nodeId);
		q.setString(2, dateTime);
		
		q.execute();
		result = q.getResultSet();
		boolean exists = result.next();
		
		int existingId = -1;
		if (exists) {
			existingId = result.getInt("instance_id");
		}
		
		result.close();
		q.close();
		
		if (exists) {
			// already exists, ask to delete old results
			this.printError("There are already results stored for this instance!");
			if (doOverwrite || confirmBoolean("Delete old results from database? (y/n)")) {
				q = conn.prepareStatement("DELETE FROM `instance` WHERE instance_id = ?");
				q.setInt(1, existingId);
				q.execute();
				this.printLine("Old results deleted!");
			} else {
				// don't overwrite
				this.printError("Skipping file");
				this.instanceBenchmarks[nodeId] = existingId;
				return;
			}
		}	
		
		// insert benchmark
		q = conn.prepareStatement("INSERT INTO `instance` (instance_node, instance_datetime, instance_cpu, " +
				"instance_memory, instance_fileio, instance_oltp) " +
				" VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
		q.setInt(1, nodeId);
		q.setString(2, dateTime);
		q.setDouble(3, averages.get("cpu"));
		q.setDouble(4, averages.get("memory"));
		q.setDouble(5, averages.get("file"));
		q.setDouble(6, averages.get("oltp"));
		q.executeUpdate();
		
		ResultSet keys = q.getGeneratedKeys();
		keys.next();
		
		this.instanceBenchmarks[nodeId] = keys.getInt(1);
		printLine("Saved instance benchmarks for node " + nodeId);
	}
	
	protected void _parseLoadFile (File file, String type, int nodes, int tenantId) throws Exception {
		this.printLine("Found load results file for `" + type + "` for " + nodes + " nodes and tenant #" + tenantId);
		
		int productId = this.getProductId(type);
		if (productId == -1) {
			throw new InvalidTypeException("Invalid type `"+ type + "`");
		}
		
		PreparedStatement q;
		ResultSet result;
		
		// check if this file already has results stored
		q = conn.prepareStatement("SELECT * FROM `load` " +
				"WHERE load_product = ? AND load_nodes = ? AND load_tenant = ?");
		q.setInt(1,  productId);
		q.setInt(2, nodes);
		q.setInt(3, tenantId);
		
		q.execute();
		result = q.getResultSet();
		boolean exists = result.next();
		
		int existingId = -1;
		if (exists) {
			existingId = result.getInt("load_id");
		}
		
		result.close();
		q.close();
		
		if (exists) {
			// already exists, ask to delete old results
			this.printError("There are already results stored for this load!");
			if (doOverwrite || confirmBoolean("Delete old results from database? (y/n)")) {
				q = conn.prepareStatement("DELETE FROM `load` WHERE load_id = ?");
				q.setInt(1, existingId);
				q.execute();
				this.printLine("Old results deleted!");
			} else {
				// don't overwrite
				this.printError("Skipping file");
				return;
			}
		}	
		
		// insert benchmark
		q = conn.prepareStatement("INSERT INTO `load` (load_product, load_nodes, load_tenant) " +
				" VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
		q.setInt(1, productId);
		q.setInt(2, nodes);
		q.setInt(3, tenantId);
		q.executeUpdate();
		
		ResultSet keys = q.getGeneratedKeys();
		keys.next();
		
		int loadId = keys.getInt(1);
		
		// insert connection with instances
		for(int node=1; node <= nodes; node++) {
			int instanceId = this.instanceBenchmarks[node];
			if (instanceId > 0) {
				q = conn.prepareStatement("INSERT INTO load_instance (`load`, instance) VALUES (?, ?)");
				q.setInt(1,  loadId);
				q.setInt(2, instanceId);
				q.executeUpdate();
			}
		}
		
		this.printLine("Parsing file...");
		
		CSVReader reader = new CSVReader(new FileReader(file), '\t');
		List<String[]> rows = reader.readAll();
		
		q = conn.prepareStatement("INSERT INTO load_results (`load`, `table`, rowCount, exectime) VALUES (?, ?, ?, ?)");
		
		int resultCount = 0;
		for (String[] row : rows) {
			if (row.length > 1) {
				String table = row[0];
				int rowCount = Integer.parseInt(row[1]);
				int execTime = Integer.parseInt(row[2]);
							
				q.setInt(1,  loadId);
				q.setString(2,  table);
				q.setInt(3,  rowCount);
				q.setInt(4,  execTime);
			}
						
			q.execute();	
			resultCount++;
		}		
		
		
		
		this.printLine("Inserted " + resultCount + " results");
	}
	
	protected void _parseBenchmarkFile (File file, String type, int nodes, int tenants, int users, int iterations) throws Exception {
		this.printLine("Found benchmark results file for `" + type + "` with " + nodes + " nodes, " + tenants + " tenants and " + users + " users");
		
		int productId = this.getProductId(type);
		if (productId == -1) {
			throw new InvalidTypeException("Invalid type `"+ type + "`");
		}
		
		PreparedStatement q;
		ResultSet result;
		
		// check if this file already has results stored
		q = conn.prepareStatement("SELECT * FROM benchmark " +
				"WHERE benchmark_product = ? AND benchmark_nodes = ? AND benchmark_users = ? AND benchmark_tenants = ?");
		q.setInt(1,  productId);
		q.setInt(2, nodes);
		q.setInt(3, users);
		q.setInt(4, tenants);
		
		q.execute();
		result = q.getResultSet();
		boolean exists = result.next();
		
		int existingId = -1;
		if (exists) {
			existingId = result.getInt("benchmark_id");
		}
		
		result.close();
		q.close();
		
		if (exists) {
			// already exists, ask to delete old results
			this.printError("There are already results stored for this benchmark!");
			if (doOverwrite || confirmBoolean("Delete old results from database? (y/n)")) {
				q = conn.prepareStatement("DELETE FROM benchmark WHERE benchmark_id = ?");
				q.setInt(1, existingId);
				q.execute();
				this.printLine("Old results deleted!");
			} else {
				// don't overwrite
				this.printError("Skipping file");
				return;
			}
		}		 
		
		// insert benchmark
		q = conn.prepareStatement("INSERT INTO benchmark (benchmark_product, benchmark_nodes, benchmark_users, " +
				"benchmark_tenants, benchmark_iterations) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
		q.setInt(1, productId);
		q.setInt(2, nodes);
		q.setInt(3, users);
		q.setInt(4, tenants);
		q.setInt(5, iterations);
		q.executeUpdate();
		
		ResultSet keys = q.getGeneratedKeys();
		keys.next();
		
		int benchmarkId = keys.getInt(1);
		
		// insert connection with instances
		for(int node=1; node <= nodes; node++) {
			int instanceId = this.instanceBenchmarks[node];
			if (instanceId > 0) {
				q = conn.prepareStatement("INSERT INTO benchmark_instance (`benchmark`, instance) VALUES (?, ?)");
				q.setInt(1,  benchmarkId);
				q.setInt(2, instanceId);
				q.executeUpdate();
			}
		}
		
		this.printLine("Parsing file...");
		
		CSVReader reader = new CSVReader(new FileReader(file), '\t');
		List<String[]> rows = reader.readAll();
		
		q = conn.prepareStatement("INSERT INTO benchmark_results (benchmark, user, iteration, `set`, query1_time, query2_time, " +
				"query3_time, query4_time, set_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
		
		int rowCount = 0;
		int queryCount = 0;
		int setCount = 0;
		int queryTotal = 0;
		int setTotal = 0;
		int failedTotal = 0;
		for (String[] row : rows) {
			if (row.length > 1) {
				int userId = Integer.parseInt(row[0]);
				int iteration = Integer.parseInt(row[1]);
				int setId = Integer.parseInt(row[2]);
				int set_time = Integer.parseInt(row[7]);			
				int[] queryTimes = new int[5];				
				
				// parse query times (row items 3-6)
				for(int i=1; i < 5; i++) {
					queryTimes[i] = Integer.parseInt(row[i+2]);
					
					if (queryTimes[i] > 0) {
						queryCount++;
						queryTotal += queryTimes[i];
						
						if (queryTimes[i] == BenchmarksScript.QUERY_TIMEOUT) {
							failedTotal++;
						}												
					}
				}
								
				if (set_time > 0) {
					setCount++;
					setTotal += set_time;
				}
				
				q.setInt(1,  benchmarkId);
				q.setInt(2,  userId);
				q.setInt(3,  iteration);
				q.setInt(4,  setId);
				q.setInt(5,  queryTimes[1]);
				q.setInt(6,  queryTimes[2]);
				q.setInt(7,  queryTimes[3]);
				q.setInt(8,  queryTimes[4]);
				q.setInt(9,  set_time);
				
				q.execute();	
				rowCount++;
			}
		}	
		
		float queryAvg = queryTotal / queryCount;
		float setAvg = setTotal / setCount;
		
		q = conn.prepareStatement("UPDATE benchmark SET benchmark_avg_querytime = ?, benchmark_avg_settime = ?, " +
				"benchmark_failed_querycount = ? WHERE benchmark_id = ?");
		q.setFloat(1, queryAvg);
		q.setFloat(2, setAvg);
		q.setInt(3, failedTotal);
		q.setInt(4, benchmarkId);
		q.executeUpdate();
		
		this.printLine("Inserted " + rowCount + " results");
	}
		
	protected void _setOptions () throws Exception {
		// results directory
		this.resultsDirectory = cliArgs.getOptionValue("results-directory", "");
		File resultsDir = new File(this.resultsDirectory, "");
		if (resultsDir.length() == 0 || resultsDir.exists() == false || resultsDir.isFile()) {
			throw new InvalidResultsDirectoryException("Specify the directory that contains the reuslts with --results-directory");
		}
		this.resultsDirectory = resultsDir.getAbsolutePath() + "/";
		this.printLine("Results directory: " + this.resultsDirectory);
		
		this.doOverwrite = cliArgs.hasOption("overwrite");
		
		alias = cliArgs.getOptionValue("alias");
		if (alias == null || alias.length() == 0) {
			alias = null;
		}
		if (alias != null) {
			printLine("Using alias: " + alias);
		}
		
		String dbUser = cliArgs.getOptionValue("user", "root");		
		String dbPassword = cliArgs.getOptionValue("password", "");
		String dbName = cliArgs.getOptionValue("database", "benchmarking_results");
		String dbHost = cliArgs.getOptionValue("host", "localhost");
		
		if (cliArgs.hasOption("password") == false) {
			// ask for password
			dbPassword = confirm("Database password: ");			
		}
		
		// try to setup DB connection
		Class.forName("com.mysql.jdbc.Driver");
		String connUrl = "jdbc:mysql://localhost:3306/" + dbName;
		printLine("Setting up connection: " + connUrl);
		conn = DriverManager.getConnection(connUrl, dbUser, dbPassword);
		printLine("Connection setup!");
	}
	
	protected int getProductId(String type) throws SQLException {
		// find product id
		PreparedStatement q = conn.prepareStatement("SELECT product_id FROM product WHERE product_type = ?");
		
		if (alias == null) {
			q.setString(1,  type);
		} else {
			q.setString(1,  alias);
		}
		
		q.execute();
		ResultSet result = q.getResultSet(); 
		
		int productId = -1;
		if (result.next()) {
			productId = result.getInt("product_id");
		}
			
		result.close();
		q.close();
		
		return productId;
	}
	
	public class InvalidTypeException extends Exception {
		public InvalidTypeException(String string) {
			super(string);
		}
	}
	
	public class InvalidResultsDirectoryException extends Exception {
		public InvalidResultsDirectoryException(String string) {
			super(string);
		}
	}
}
