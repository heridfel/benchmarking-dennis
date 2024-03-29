package topicus.loadtenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class VerticaManageTenant extends ManageTenant {

	@Override
	public void deleteDataFromTable(Connection conn, String tableName, String tenantField, int tenantId)
			throws SQLException {
				
		PreparedStatement q = conn.prepareStatement("DELETE /*+ direct */ FROM " + tableName + " WHERE " + tenantField + " = ?");
		q.setInt(1,  tenantId);
		
		try {
			q.execute();
		} catch (SQLException e) {
			if (e.getMessage().toLowerCase().indexOf("no super projection found") == -1) {
				throw e;
			}
		}
		
		// purge old data
		this.conn.createStatement().execute("SELECT PURGE()");
	}

	@Override
	public void deleteDataFromClosure(Connection conn, int beginKey, int endKey)
			throws SQLException {
		PreparedStatement q = conn.prepareStatement("DELETE /*+ direct */ FROM closure_organisatie " +
				"WHERE organisatie_key >= ? AND organisatie_key <= ?");
		q.setInt(1,  beginKey);
		q.setInt(2,  endKey);
		
		try {
			q.execute();
		} catch (SQLException e) {
			if (e.getMessage().toLowerCase().indexOf("no super projection found") == -1) {
				throw e;
			}
		}
		
		// purge old data
		this.conn.createStatement().execute("SELECT PURGE()");
	}

}
