/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.lynxe.tool.database.action;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.database.DataSourceService;
import com.alibaba.cloud.ai.lynxe.tool.database.DatabaseRequest;

public class ExecuteSqlAction extends AbstractDatabaseAction {

	private static final Logger log = LoggerFactory.getLogger(ExecuteSqlAction.class);

	@Override
	public ToolExecuteResult execute(DatabaseRequest request, DataSourceService dataSourceService) {
		String query = request.getQuery();
		String datasourceName = request.getDatasourceName();
		List<Object> parameters = request.getParameters();

		if (query == null || query.trim().isEmpty()) {
			log.warn("ExecuteSqlAction failed: missing query statement, datasourceName={}", datasourceName);
			return new ToolExecuteResult("Datasource: " + (datasourceName != null ? datasourceName : "default")
					+ "\nError: Missing query statement");
		}

		// Check if we should use prepared statements (parameters provided)
		if (parameters != null && !parameters.isEmpty()) {
			return executePreparedStatement(query, parameters, datasourceName, dataSourceService);
		}
		else {
			return executeRegularStatement(query, datasourceName, dataSourceService);
		}
	}

	/**
	 * Execute SQL using prepared statements with parameters Supports both single-row and
	 * batch (multi-row) inserts
	 */
	private ToolExecuteResult executePreparedStatement(String query, List<Object> parameters, String datasourceName,
			DataSourceService dataSourceService) {
		// Check if this is a batch insert (parameters is a list of lists)
		boolean isBatchMode = isBatchParameters(parameters);

		if (isBatchMode) {
			return executeBatchPreparedStatement(query, parameters, datasourceName, dataSourceService);
		}
		else {
			return executeSingleRowPreparedStatement(query, parameters, datasourceName, dataSourceService);
		}
	}

	/**
	 * Check if parameters represent a batch (list of lists) or single row (flat list)
	 */
	private boolean isBatchParameters(List<Object> parameters) {
		if (parameters == null || parameters.isEmpty()) {
			return false;
		}
		// Check if first element is a List (indicating batch mode)
		Object firstElement = parameters.get(0);
		return firstElement instanceof List<?>;
	}

	/**
	 * Execute batch prepared statement (multi-row insert)
	 */
	@SuppressWarnings("unchecked")
	private ToolExecuteResult executeBatchPreparedStatement(String query, List<Object> parameters,
			String datasourceName, DataSourceService dataSourceService) {
		// Validate that all elements are lists
		int placeholderCount = countPlaceholders(query);
		List<List<Object>> batchParameters = new ArrayList<>();

		for (Object param : parameters) {
			if (!(param instanceof List)) {
				String errorMsg = String.format(
						"Batch mode requires all parameters to be lists. Found non-list parameter at index %d",
						parameters.indexOf(param));
				log.error("ExecuteSqlAction batch validation failed: {}", errorMsg);
				return new ToolExecuteResult("Datasource: " + (datasourceName != null ? datasourceName : "default")
						+ "\nError: " + errorMsg);
			}

			List<Object> rowParams = (List<Object>) param;
			if (rowParams.size() != placeholderCount) {
				String errorMsg = String.format(
						"Parameter count mismatch in batch row %d: SQL query has %d placeholder(s) (?), but %d parameter(s) provided in this row.",
						batchParameters.size(), placeholderCount, rowParams.size());
				log.error("ExecuteSqlAction batch row validation failed: {}", errorMsg);
				return new ToolExecuteResult("Datasource: " + (datasourceName != null ? datasourceName : "default")
						+ "\nError: " + errorMsg);
			}
			batchParameters.add(rowParams);
		}

		if (batchParameters.isEmpty()) {
			return new ToolExecuteResult("Datasource: " + (datasourceName != null ? datasourceName : "default")
					+ "\nError: No batch rows provided");
		}

		List<String> results = new ArrayList<>();
		try (Connection conn = datasourceName != null && !datasourceName.trim().isEmpty()
				? dataSourceService.getConnection(datasourceName) : dataSourceService.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(query)) {

			// Add each row to the batch
			for (List<Object> rowParams : batchParameters) {
				// Set parameters for this row
				for (int i = 0; i < rowParams.size(); i++) {
					Object param = rowParams.get(i);
					if (param == null) {
						pstmt.setNull(i + 1, java.sql.Types.NULL);
					}
					else {
						pstmt.setObject(i + 1, param);
					}
				}
				// Add to batch
				pstmt.addBatch();
			}

			log.info("Executing batch prepared statement with {} rows, {} parameters per row", batchParameters.size(),
					placeholderCount);

			// Execute batch
			int[] updateCounts = pstmt.executeBatch();

			// Calculate total affected rows
			int totalAffectedRows = 0;
			for (int count : updateCounts) {
				if (count >= 0) {
					totalAffectedRows += count;
				}
			}

			results.add(String.format("Batch execution successful. %d row(s) processed, %d row(s) affected",
					batchParameters.size(), totalAffectedRows));

			log.info(
					"ExecuteSqlAction (batch prepared) completed successfully, datasourceName={}, rows={}, affected={}",
					datasourceName, batchParameters.size(), totalAffectedRows);
			String resultContent = "Datasource: " + (datasourceName != null ? datasourceName : "default") + "\n"
					+ String.join("\n---\n", results);
			return new ToolExecuteResult(resultContent);
		}
		catch (SQLException e) {
			log.error("ExecuteSqlAction (batch prepared) failed with SQLException, datasourceName={}, error={}",
					datasourceName, e.getMessage(), e);
			return new ToolExecuteResult("Datasource: " + (datasourceName != null ? datasourceName : "default")
					+ "\nBatch prepared SQL execution failed: " + e.getMessage());
		}
	}

	/**
	 * Execute single-row prepared statement
	 */
	private ToolExecuteResult executeSingleRowPreparedStatement(String query, List<Object> parameters,
			String datasourceName, DataSourceService dataSourceService) {
		// Validate parameter count matches placeholder count
		int placeholderCount = countPlaceholders(query);
		if (placeholderCount != parameters.size()) {
			String errorMsg = String
				.format("Parameter count mismatch: SQL query has %d placeholder(s) (?), but %d parameter(s) provided. "
						+ "Query: %s", placeholderCount, parameters.size(), query);
			log.error("ExecuteSqlAction parameter validation failed: {}", errorMsg);
			return new ToolExecuteResult(
					"Datasource: " + (datasourceName != null ? datasourceName : "default") + "\nError: " + errorMsg);
		}

		List<String> results = new ArrayList<>();
		try (Connection conn = datasourceName != null && !datasourceName.trim().isEmpty()
				? dataSourceService.getConnection(datasourceName) : dataSourceService.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(query)) {

			// Set parameters
			for (int i = 0; i < parameters.size(); i++) {
				Object param = parameters.get(i);
				if (param == null) {
					pstmt.setNull(i + 1, java.sql.Types.NULL);
				}
				else {
					pstmt.setObject(i + 1, param);
				}
			}

			log.info("Executing prepared statement with {} parameters", parameters.size());
			boolean hasResultSet = pstmt.execute();

			if (hasResultSet) {
				try (ResultSet rs = pstmt.getResultSet()) {
					results.add(formatResultSet(rs));
				}
			}
			else {
				int updateCount = pstmt.getUpdateCount();
				results.add("Execution successful. Affected rows: " + updateCount);
			}

			log.info("ExecuteSqlAction (prepared) completed successfully, datasourceName={}", datasourceName);
			String resultContent = "Datasource: " + (datasourceName != null ? datasourceName : "default") + "\n"
					+ String.join("\n---\n", results);
			return new ToolExecuteResult(resultContent);
		}
		catch (SQLException e) {
			log.error("ExecuteSqlAction (prepared) failed with SQLException, datasourceName={}, error={}",
					datasourceName, e.getMessage(), e);
			return new ToolExecuteResult("Datasource: " + (datasourceName != null ? datasourceName : "default")
					+ "\nPrepared SQL execution failed: " + e.getMessage());
		}
	}

	/**
	 * Execute SQL using regular statements (backward compatibility)
	 */
	private ToolExecuteResult executeRegularStatement(String query, String datasourceName,
			DataSourceService dataSourceService) {
		String[] statements = query.split(";");
		List<String> results = new ArrayList<>();
		try (Connection conn = datasourceName != null && !datasourceName.trim().isEmpty()
				? dataSourceService.getConnection(datasourceName) : dataSourceService.getConnection();
				Statement stmt = conn.createStatement()) {
			for (String sql : statements) {
				sql = sql.trim();
				if (sql.isEmpty())
					continue;
				boolean hasResultSet = stmt.execute(sql);
				if (hasResultSet) {
					try (ResultSet rs = stmt.getResultSet()) {
						results.add(formatResultSet(rs));
					}
				}
				else {
					int updateCount = stmt.getUpdateCount();
					results.add("Execution successful. Affected rows: " + updateCount);
				}
			}
			log.info("ExecuteSqlAction (regular) completed successfully, datasourceName={}, statements={}",
					datasourceName, statements.length);
			String resultContent = "Datasource: " + (datasourceName != null ? datasourceName : "default") + "\n"
					+ String.join("\n---\n", results);
			return new ToolExecuteResult(resultContent);
		}
		catch (SQLException e) {
			log.error("ExecuteSqlAction (regular) failed with SQLException, datasourceName={}, error={}",
					datasourceName, e.getMessage(), e);
			return new ToolExecuteResult("Datasource: " + (datasourceName != null ? datasourceName : "default")
					+ "\nSQL execution failed: " + e.getMessage());
		}
	}

	private String formatResultSet(ResultSet rs) throws SQLException {
		int columnCount = rs.getMetaData().getColumnCount();
		// Collect column names
		List<String> columnNames = new ArrayList<>();
		for (int i = 1; i <= columnCount; i++) {
			columnNames.add(rs.getMetaData().getColumnName(i));
		}
		// Check if we have data and collect rows
		boolean hasData = false;
		List<List<String>> rows = new ArrayList<>();
		while (rs.next()) {
			hasData = true;
			List<String> row = new ArrayList<>();
			for (int i = 1; i <= columnCount; i++) {
				Object val = rs.getObject(i);
				String cellValue = (val == null) ? "NULL" : val.toString();
				row.add(escapeMarkdownTableCell(cellValue));
			}
			rows.add(row);
		}
		// Format output based on whether we have data
		if (hasData) {
			// Build markdown table
			StringBuilder table = new StringBuilder();
			// Header row
			table.append("| ");
			for (int i = 0; i < columnNames.size(); i++) {
				table.append(escapeMarkdownTableCell(columnNames.get(i)));
				if (i < columnNames.size() - 1) {
					table.append(" | ");
				}
			}
			table.append(" |\n");
			// Separator row
			table.append("| ");
			for (int i = 0; i < columnNames.size(); i++) {
				table.append("---");
				if (i < columnNames.size() - 1) {
					table.append(" | ");
				}
			}
			table.append(" |\n");
			// Data rows
			for (List<String> row : rows) {
				table.append("| ");
				for (int i = 0; i < row.size(); i++) {
					table.append(row.get(i));
					if (i < row.size() - 1) {
						table.append(" | ");
					}
				}
				table.append(" |\n");
			}
			return table.toString();
		}
		else {
			// Empty result set: return specific format
			String columnNamesStr = String.join(",", columnNames);
			return "returnColumn: " + columnNamesStr + " \nresultSet: No rows found.";
		}
	}

	private String escapeMarkdownTableCell(String cell) {
		if (cell == null) {
			return "";
		}
		// Replace pipe characters and newlines to prevent markdown table breakage
		return cell.replace("|", "\\|").replace("\n", "\\n").replace("\r", "\\r");
	}

	/**
	 * Count the number of ? placeholders in SQL query Note: This is a simple
	 * implementation that counts all ? characters. For production use, a more
	 * sophisticated parser would be needed to handle question marks inside string
	 * literals, comments, etc.
	 */
	private int countPlaceholders(String query) {
		if (query == null || query.trim().isEmpty()) {
			return 0;
		}
		int count = 0;
		for (int i = 0; i < query.length(); i++) {
			if (query.charAt(i) == '?') {
				count++;
			}
		}
		return count;
	}

}
