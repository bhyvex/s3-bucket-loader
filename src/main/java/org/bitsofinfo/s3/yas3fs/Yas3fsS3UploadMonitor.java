	package org.bitsofinfo.s3.yas3fs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.bitsofinfo.s3.worker.WriteBackoffMonitor;
import org.bitsofinfo.s3.worker.WriteErrorMonitor;
import org.bitsofinfo.s3.worker.WriteMonitor;
import org.bitsofinfo.s3.worker.WriteMonitorError;

/**
 * Monitors the Yas3fs log for entries like this looking for the s3_queue being zero
 * meaning that there are no uploads to s3 in progress. It also can act as a WriteBackoffMonitor
 * to monitor when the total number in s3_queue gets to high.
 * 
 * INFO entries, mem_size, disk_size, download_queue, prefetch_queue, s3_queue: 1, 0, 0, 0, 0, 0
 * 
 * Also has the ability to monitor the number of outgoing 443 SSL connections (to S3)
 * and can instruct to backoff if these are > that whatever the max is configured for
 *	
 * @author bitsofinfo
 *
 */
public class Yas3fsS3UploadMonitor implements WriteMonitor, WriteBackoffMonitor, WriteErrorMonitor, Runnable {
	
	private static final Logger logger = Logger.getLogger(Yas3fsS3UploadMonitor.class);

	private long checkEveryMS = 10000;
	private int isIdleWhenNZeroUploads = 0; // count of the total number of s3UploadCounts entries must be ZERO to declare we are idel
	
	private String pathToLogFile = null;
	private boolean running = true;
	private Thread monitorThread = null;
	private String latestLogTail = null;
	
	private SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
	
	private Integer backoffWhenTotalS3Uploads = 10;
	
	private Integer backoffWhenTotalHTTPSConns = 10;
	private int latestHTTPSConnTotal = 0;
	
	private Integer backoffWhenMultipartUploads = 2;
	
	private Stack<Integer> s3UploadCounts = new Stack<Integer>();
	
	public Yas3fsS3UploadMonitor() {
		monitorThread = new Thread(this);
	}
	
	
	public Yas3fsS3UploadMonitor(String pathToLogFile, long checkEveryMS) {
		this.pathToLogFile = pathToLogFile;	
		this.checkEveryMS = checkEveryMS;
		monitorThread = new Thread(this);
	}
	
	
	public Yas3fsS3UploadMonitor(String pathToLogFile, long checkEveryMS, int isIdleWhenNZeroUploads) {
		this.pathToLogFile = pathToLogFile;	
		this.checkEveryMS = checkEveryMS;
		this.isIdleWhenNZeroUploads = isIdleWhenNZeroUploads;
		monitorThread = new Thread(this);
	}
	
	
	public Yas3fsS3UploadMonitor(String pathToLogFile, int backoffWhenTotalS3Uploads, long checkEveryMS) {
		this.pathToLogFile = pathToLogFile;	
		this.checkEveryMS = checkEveryMS;
		this.backoffWhenTotalS3Uploads = backoffWhenTotalS3Uploads;
		monitorThread = new Thread(this);
	}
	
	public void start() {
		monitorThread.start();
	}
	
	public void destroy() {
		this.running = false;
	}

	public void run() {
		while(running) {
			try {
				
				Thread.currentThread().sleep(this.checkEveryMS);
				
				
				try {
					/**
					 * Check the log file
					 */
					RandomAccessFile file = new RandomAccessFile(new File(pathToLogFile), "r");
					byte[] buffer = new byte[32768]; // read ~32k
					if (file.length() >= buffer.length) {
						file.seek(file.length()-buffer.length);
					}
					file.read(buffer, 0, buffer.length);	
					file.close();
					
					this.latestLogTail = new String(buffer,"UTF-8");
				} catch(Exception e) {
					logger.error("Unexpected error tailing yas3fs.log: " + this.pathToLogFile + " " + e.getMessage(),e);
				}
				
				try {
					/**
					 * Check netstat
					 */
					
					CommandLine cmdLine = new CommandLine("netstat");
					cmdLine.addArgument("-na");
					
					final StringWriter stdOut = new StringWriter();
					final StringWriter stdErr = new StringWriter();
					DefaultExecutor executor = new DefaultExecutor();
					executor.setStreamHandler(new ExecuteStreamHandler() {
							public void setProcessOutputStream(InputStream is) throws IOException {IOUtils.copy(is, stdOut, "UTF-8");}
							public void setProcessErrorStream(InputStream is) throws IOException {IOUtils.copy(is, stdErr, "UTF-8");}
							public void stop() throws IOException {}
							public void start() throws IOException {}
							public void setProcessInputStream(OutputStream os) throws IOException {}
						});
						
					logger.trace("Executing: " + cmdLine.toString());
						
					int exitValue = executor.execute(cmdLine);
					if (exitValue > 0) {
						logger.error("Netstat check ERROR: exitCode: "+exitValue+" cmd=" + cmdLine.toString());
					}
					
					String netstatOutput = stdOut.toString();
					Pattern netstatPattern = Pattern.compile("443\\s+ESTABLISHED");
					Matcher netstatMatcher = netstatPattern.matcher(netstatOutput);
					int total = 0;
					while (netstatMatcher.find()) {
						total += 1;
					}
					
					logger.trace("Latest total of Netstat outgoing HTTPS connections = " + total);
					this.latestHTTPSConnTotal = total;
					
				} catch(Exception e) {
					logger.error("Unexpected error netstating current HTTPS conns: " + this.pathToLogFile + " " + e.getMessage(),e);
				}
				
			} catch(Exception e) {
				logger.error("Unexpected error: " + this.pathToLogFile + " " + e.getMessage(),e);
			}
		}
	}
	
	public int getS3UploadQueueSize() {
		if (this.latestLogTail != null) {
			Pattern s3QueueSizePatten = Pattern.compile(".+s3_queue: \\d+, \\d+, \\d+, \\d+, \\d+, (\\d+).*");
			Matcher m = s3QueueSizePatten.matcher(this.latestLogTail);
			int lastMatch = -1;
			
			while (m.find()) {
			    lastMatch = Integer.valueOf(m.group(1).trim());
			}
			
			return lastMatch;
		}
		
		return -1;
	}
	
	public boolean writesShouldBackoff() {
		
		int currentMultipartUploads = this.getCurrentMultipartUploads();
		int currentS3UploadSize = this.getS3UploadQueueSize();
		logger.debug("Latest Yas3fs s3_queue size = " + currentS3UploadSize);
		logger.debug("Latest Netstat outgoing HTTPS connections = " + latestHTTPSConnTotal);
		logger.debug("Latest Yas3fs multipart uploads = " + currentMultipartUploads);
		
		if (currentMultipartUploads >= this.backoffWhenMultipartUploads) {
			logger.debug("writesShouldBackoff() currentMultipartUploads=" + currentMultipartUploads + 
					" and backoffWhenMultipartUploads=" + this.backoffWhenMultipartUploads);
			return true;
		}
		
		
		if (this.latestHTTPSConnTotal >= this.backoffWhenTotalHTTPSConns) {
			logger.debug("writesShouldBackoff() latestHTTPSConnTotal=" + latestHTTPSConnTotal + 
					" and backoffWhenTotalHTTPSConns=" + this.backoffWhenTotalHTTPSConns);
			return true;
		}
		

		if (currentS3UploadSize >= this.backoffWhenTotalS3Uploads) {
			logger.debug("writesShouldBackoff() currentS3UploadSize=" + currentS3UploadSize + 
					" and backoffWhenTotalS3Uploads=" + this.backoffWhenTotalS3Uploads);
			return true;
		}
		
		return false;
	}
	
	public boolean writesAreComplete() {
		// get the latest s3upload queue size
		int s3UploadQueueSize = this.getS3UploadQueueSize();
		
		// add it to our list (most recent -> oldest)
		this.s3UploadCounts.push(s3UploadQueueSize);
		
		int count = -1;
		
		// if we have enought upload count history...
		if (this.s3UploadCounts.size() > this.isIdleWhenNZeroUploads) {
			
			// clone it
			Stack<Integer> toScan = (Stack<Integer>)this.s3UploadCounts.clone();
			
			// look through N past upload counts we have checked
			// and add them all up... (the stack is a LIFO stack)
			// so most recent -> oldest
			count = 0; // init to zero....
			for (int i=0; i<this.isIdleWhenNZeroUploads; i++) {
				count += toScan.pop();
			}
			
			// if they all add up to ZERO, then yas3fs is not uploading anymore.
			if (count == 0) {
				logger.debug("writesAreComplete() YES: count = 0");
				return true;
			}
		}
		
		
		logger.debug("writesAreComplete() NO: count = " + count);
		return false;
		
		
	}

	public void setCheckEveryMS(long checkEveryMS) {
		this.checkEveryMS = checkEveryMS;
	}

	public void setIsIdleWhenNZeroUploads(int isIdleWhenNZeroUploads) {
		this.isIdleWhenNZeroUploads = isIdleWhenNZeroUploads;
	}

	public void setPathToLogFile(String pathToLogFile) {
		this.pathToLogFile = pathToLogFile;
	}

	public void setBackoffWhenTotalS3Uploads(Integer backoffWhenTotalS3Uploads) {
		this.backoffWhenTotalS3Uploads = backoffWhenTotalS3Uploads;
	}

	@Override
	public Set<WriteMonitorError> getWriteErrors() {
		Set<WriteMonitorError> errs = new HashSet<WriteMonitorError>();
		
		logger.debug("getWriteErrors() checking for errors in Yas3fs logfile....");
		
		
		String[] failuresToMatch = 
				new String[]
					{
					// 2014-10-22 19:11:29,799 ERROR PLUGIN do_cmd_on_s3_now_w_retries FAILED
					"(\\d{4}-\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{1,2}:\\d{1,2},\\d{3}).+(do_cmd_on_s3_now_w_retries FAILED.*)",
					
					};
		
		
		if (this.latestLogTail != null) {
			
			for (String pattern : failuresToMatch) {
			
				try {
					Pattern errorPatterns = Pattern.compile(pattern);
					Matcher m = errorPatterns.matcher(this.latestLogTail);
	
					while (m.find()) {
					    String date = m.group(1).trim();
					    Date timestamp = logDateFormat.parse(date);
					    String msg = m.group(2).trim();
					    
					    logger.debug("getWriteErrors() found Yas3FSError: " + date + " msg: " + msg);
					    errs.add(new WriteMonitorError(timestamp,msg));
					    
					}
				} catch(Exception e) {
					logger.error("getWriteErrors() unexpected error attempting" +
							" to parse Yas3fs log file for ERRORs: " + e.getMessage(),e);
				}
			}
		}
		
		return errs;
	}
	
	
	private int getCurrentMultipartUploads() {
		
		if (this.latestLogTail != null) {

			try {
				String toCheck = this.latestLogTail;
				Pattern p = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{1,2}:\\d{1,2},\\d{3}.+multipart_uploads_in_progress\\s+=\\s+(\\d+)");
				Matcher m = p.matcher(toCheck);

				int lastMpTotal = 0;
				while (m.find()) {
				    String mpTotal = m.group(1).trim();
				    lastMpTotal = Integer.valueOf(mpTotal);
				}
				
				return lastMpTotal;
				
			} catch(Exception e) {
				logger.error("getCurrentMultipartUploads() unexpected error attempting" +
						" to parse Yas3fs log file for multipart_uploads_in_progress: " + e.getMessage(),e);
			}

		}
		
		return 0;
		
	}
	
	public static void main(String[] args) throws Exception {
		
		Yas3fsS3UploadMonitor m = new Yas3fsS3UploadMonitor();
		m.setCheckEveryMS(10000);
		m.setPathToLogFile("//testyaslog.log");
		
		m.start();
		

		
		while(true) {
			Thread.currentThread().sleep(10000);
			int x = m.getCurrentMultipartUploads();

			int v = 1;
		}
		
		/*
		CommandLine cmdLine = new CommandLine("netstat");
		cmdLine.addArgument("-na");
		
		final StringWriter stdOut = new StringWriter();
		final StringWriter stdErr = new StringWriter();
		DefaultExecutor executor = new DefaultExecutor();
		executor.setStreamHandler(new ExecuteStreamHandler() {
				public void setProcessOutputStream(InputStream is) throws IOException {IOUtils.copy(is, stdOut, "UTF-8");}
				public void setProcessErrorStream(InputStream is) throws IOException {IOUtils.copy(is, stdErr, "UTF-8");}
				public void stop() throws IOException {}
				public void start() throws IOException {}
				public void setProcessInputStream(OutputStream os) throws IOException {}
			});
			
		logger.debug("Executing: " + cmdLine.toString());
			
		int exitValue = executor.execute(cmdLine);
		if (exitValue > 0) {
			logger.error("Netstat check ERROR: exitCode: "+exitValue+" cmd=" + cmdLine.toString());
		}
		
		String netstatOutput = stdOut.toString();
		Pattern netstatPattern = Pattern.compile("443\\s+ESTABLISHED");
		Matcher netstatMatcher = netstatPattern.matcher(netstatOutput);
		int total = 0;
		while (netstatMatcher.find()) {
			total += 1;
		}
		
		System.out.println(total);
		*/
	}


	public Integer getBackoffWhenTotalHTTPSConns() {
		return backoffWhenTotalHTTPSConns;
	}


	public void setBackoffWhenTotalHTTPSConns(Integer backoffWhenTotalSSLConns) {
		this.backoffWhenTotalHTTPSConns = backoffWhenTotalSSLConns;
	}


	public Integer getBackoffWhenMultipartUploads() {
		return backoffWhenMultipartUploads;
	}


	public void setBackoffWhenMultipartUploads(Integer backoffWhenMultipartUploads) {
		this.backoffWhenMultipartUploads = backoffWhenMultipartUploads;
	}
	
	
	
}
