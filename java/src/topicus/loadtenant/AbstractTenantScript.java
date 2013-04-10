package topicus.loadtenant;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.TreeMap;

import au.com.bytecode.opencsv.CSVWriter;

import topicus.DatabaseScript;
import topicus.databases.AbstractDatabase;

public abstract class AbstractTenantScript extends DatabaseScript {
	protected AbstractManageTenant manageTenant;
	protected int tenantId = -1;
	
	protected Connection conn;
	
	protected LinkedHashMap<String, String> tables = new LinkedHashMap<String, String>();

	public AbstractTenantScript(String type, AbstractDatabase database, AbstractManageTenant manageTenant) {
		super(type, database);
		this.manageTenant = manageTenant;		
			
		tables.put("dim_administratie", "adm_data.tbl");
		tables.put("dim_grootboek", "gb_data.tbl");
		tables.put("dim_kostenplaats", "kp_data.tbl");
		tables.put("organisatie", "org_data.tbl");
		tables.put("closure_organisatie", "closure_org_data.tbl");
		tables.put("fact_exploitatie", "fe_data.tbl");
	}

	protected void _deleteOldData(String tableName) throws SQLException {
		this.printLine("Deleting old tenant data from `" + tableName + "`");
		
		if (tableName.equals("closure_organisatie")) {
			// number of rows in the organisation table
			final int ORG_ROW_COUNT = 988;
			final int ORG_BEGIN_PK = 752;
			
			int beginPk = (this.tenantId - 1) * ORG_ROW_COUNT + ORG_BEGIN_PK;
			int endPk = beginPk + ORG_ROW_COUNT;
			
			this.manageTenant.deleteDataFromClosure(beginPk, endPk);
		} else {
			String tenantField = "";
			
			if (tableName.equals("dim_administratie")) tenantField = "a_tenant";
			if (tableName.equals("dim_grootboek")) tenantField = "gb_tenant";
			if (tableName.equals("dim_kostenplaats")) tenantField = "kp_tenant";
			if (tableName.equals("organisatie")) tenantField = "afnemer";
			if (tableName.equals("fact_exploitatie")) tenantField = "tenant_key";
			
			this.manageTenant.deleteDataFromTable(tableName, tenantField, this.tenantId);
		}		
		
		this.printLine("Old data deleted");
	}
	
	
		
	protected boolean isTenantDeployed () throws SQLException {
		boolean ret = false;
		
		PreparedStatement q = this.conn.prepareStatement("SELECT a_tenant FROM dim_administratie WHERE a_tenant = ? limit 1");
		q.setInt(1,  this.tenantId);
		
		ResultSet result = q.executeQuery();
		
		if (result.next()) {
			ret = true;
		}
		
		return ret;
	}
	
	protected void _setOptions () throws Exception {	
		this.tenantId = Integer.parseInt(this.cliArgs.getOptionValue("tenant-id", "0"));
		if (this.tenantId < 1) {
			this.printError("Invalid tenant ID specified");
			System.exit(0);
		}
		
		this.printLine("Tenant ID set to: " + this.tenantId);
	}
}
