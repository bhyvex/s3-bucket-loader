package org.bitsofinfo.s3.toc;

import java.io.File;

import org.apache.log4j.Logger;
import org.bitsofinfo.s3.cmd.TocPathOpResult;
import org.bitsofinfo.s3.worker.WorkerState;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ObjectMetadata;

public class ValidatingTOCPayloadHandler implements TOCPayloadHandler {

	private static final Logger logger = Logger.getLogger(ValidatingTOCPayloadHandler.class);
	
	private String targetDirectoryRootPath = null;
	
	public static enum MODE { validateEverywhere, validateLocallyOnly, validateS3Only, validateLocallyThenS3OnFailure }
	
	private MODE validateMode = MODE.validateLocallyThenS3OnFailure;
	private AmazonS3Client s3Client = null;
	private String s3BucketName = null;
	
	public ValidatingTOCPayloadHandler() {
	}

	
	public void handlePayload(TOCPayload payload, WorkerState workerState) throws Exception {

		try {
			
			// VALIDATE LOCAL first, then S3
			if (validateMode == MODE.validateLocallyThenS3OnFailure) {
				
				TocPathOpResult localCheck = validateLocally(payload);
				
				if (localCheck.success) {
					workerState.addTocPathValidated(localCheck);

				// failed? check s3
				} else {
					logger.error("validateLocally() failed, falling back to S3 check..." + payload.tocInfo.getPath());
					TocPathOpResult s3Check = validateOnS3(payload);
					
					if (s3Check.success) {
						workerState.addTocPathValidated(s3Check);
						
					// both failed....
					} else {
						workerState.addTocPathValidateFailure(new TocPathOpResult(payload.mode, false, payload.tocInfo.path, 
								"localFS["+localCheck.success+"]_then_s3["+s3Check.success+"]", "failed: s3["+s3Check.message+"] local["+localCheck.message+"]"));
					}
					
				}
				
				return;
			}
			
			// VALIDATE BOTH
			if (validateMode == MODE.validateEverywhere) {
				TocPathOpResult localCheck = validateLocally(payload);
				TocPathOpResult s3Check = validateOnS3(payload);
				
				if (localCheck.success && s3Check.success) {
					workerState.addTocPathValidated(new TocPathOpResult(payload.mode, true, payload.tocInfo.path, "localFS_and_s3", "both validated ok"));
				} else {
					workerState.addTocPathValidateFailure(new TocPathOpResult(payload.mode, false, payload.tocInfo.path, 
							"localFS["+localCheck.success+"]_and_s3["+s3Check.success+"]", "failed: s3["+s3Check.message+"] local["+localCheck.message+"]"));
				}
				
				return;
			}
			
			// VALIDATE LOCAL ONLY
			if (validateMode == MODE.validateLocallyOnly) {
				TocPathOpResult localCheck = validateLocally(payload);
				
				if (localCheck.success) {
					workerState.addTocPathValidated(localCheck);
				} else {
					workerState.addTocPathValidateFailure(localCheck);
				}
				
				return;
			}
			
			
			// VALIDATE S3 ONLY
			if (validateMode == MODE.validateS3Only) {
				TocPathOpResult s3Check = validateOnS3(payload);
				
				if (s3Check.success) {
					workerState.addTocPathValidated(s3Check);
				} else {
					workerState.addTocPathValidateFailure(s3Check);
				}
				
				return;
			}

			
		} catch(Exception e) {
			
			workerState.addTocPathValidateFailure(
					new TocPathOpResult(payload.mode, false, payload.tocInfo.path, "validation_error", "exception: " + e.getMessage()));
			
			logger.error("File validation exception: " + e.getMessage(),e);
		}
	}

	
	public void setTargetDirectoryRootPath(String targetDirectoryRootPath) {
		this.targetDirectoryRootPath = targetDirectoryRootPath;
	}

	
	public void handlePayload(TOCPayload payload) throws Exception {
		throw new UnsupportedOperationException("ValidatingTOCPayloadHandler does not " +
				"support this method variant, call me through Worker");
	}

	public AmazonS3Client getS3Client() {
		return s3Client;
	}

	public void setS3Client(AmazonS3Client s3Client) {
		this.s3Client = s3Client;
	}

	public String getS3BucketName() {
		return s3BucketName;
	}

	public void setS3BucketName(String s3BucketName) {
		this.s3BucketName = s3BucketName;
	}
	
	/**
	 * Validates the file locally on disk
	 * 
	 * @param payload
	 * @return
	 */
	private TocPathOpResult validateLocally(TOCPayload payload) {
		
		String targetPath = null;
		
		try {
			targetPath = (targetDirectoryRootPath + payload.tocInfo.getPath()).replaceAll("//", "/");
			
			logger.debug("validateLocally() " + targetPath);
			
			File toCheck = new File(targetPath);
			
			boolean exists = false;
			
			// does it exist?
			int attempts = 0; 
			int maxAttempts = 5;
			while(attempts < maxAttempts) {
				attempts++;
				
				if (toCheck.exists()) {
					exists = true;
					break;
				} 
				
				Thread.currentThread().sleep(2000);
			}
		
			if (!exists) {
				logger.error("validateLocally() File validation failed, path does not exist! " + targetPath);
				return new TocPathOpResult(payload.mode, false, targetPath, "local.check.exists", "!exists");
			}
			
			
			// TOC says directory but local does not?? error
			if (payload.tocInfo.isDirectory && !toCheck.isDirectory()) {
				logger.error("validateLocally() Path validation failed, TOC states path should be directory, local fs does not! " + targetPath);;
				return new TocPathOpResult(payload.mode, false, targetPath, "local.check.TOCDir_isa_LocalDir", "false");
			}
			
			// otherwise... must be a file, check size
			if (toCheck.isFile()) {
				
				if (toCheck.length() != payload.tocInfo.getSize()) {
		
					logger.error("validateLocally() File validation failed, file size does not match! " +
							"" + targetPath + " expected:" + payload.tocInfo.size + " actual:" + toCheck.length());;
							
					return new TocPathOpResult(payload.mode, false, targetPath,
									"local.check.file_size", "expected:"+ payload.tocInfo.size + " actual:"+toCheck.length());
				}
			}

			// SUCCESS! if we got here we are OK
			return new TocPathOpResult(payload.mode, true, targetPath, "local.check.exists", "ok");
			
			
		} catch(Exception e) {
			logger.error("validateLocally() Unexpected exception: " + e.getMessage(),e);
			return new TocPathOpResult(payload.mode, false, targetPath, "local.check.error", "exception: " + e.getMessage());

		}
	}
	
	private TocPathOpResult validateOnS3(TOCPayload payload) {

		try {
			String keyToCheck = toc2Key(payload.tocInfo.getPath(),payload.tocInfo.isDirectory);
			logger.debug("validateOnS3() " + keyToCheck);
			
			ObjectMetadata md = s3Client.getObjectMetadata(getS3BucketName(), keyToCheck);
			
			// size not match!
			if (payload.tocInfo.size != md.getContentLength()) {
				
				logger.error("validateOnS3() S3 object length does not match! " +
						"" + keyToCheck + " expected:" + payload.tocInfo.size + " actual:" + md.getContentLength());;
						
				return new TocPathOpResult(payload.mode, false, payload.tocInfo.getPath(),
								"s3.check.content.length", "expected:"+ payload.tocInfo.size + " actual:"+md.getContentLength());

			} 
			
			// SUCCESS (no 404 so size matches and it exists)
			return new TocPathOpResult(payload.mode, true, payload.tocInfo.getPath(), "s3.check", "ok");
			
		} catch(AmazonS3Exception e) {
			
			// 404
			if (e.getStatusCode() == 404) {
				
				logger.error("validateOnS3() " + payload.tocInfo.getPath() + " s3check returned 404");
				
				return new TocPathOpResult(payload.mode, false, payload.tocInfo.getPath(),
						"s3.check.404", "key not found 404 at " + this.getS3BucketName());
				
			// other error
			} else {
				
				logger.error("validateOnS3() " + payload.tocInfo.getPath() + " unexpected error: " + e.getMessage(),e);
				
				return new TocPathOpResult(payload.mode, false, payload.tocInfo.getPath(),
						"s3.check.error", "error getting object metadata: " + e.getMessage());
			}
		}
		
	}
	
	
	private static String toc2Key(String tocPath, boolean isDir) {
		
		String key = tocPath;
		
		// strip leading /
		if (key.startsWith("/")) {
			key = key.substring(1,key.length());
		}
		
		if (isDir) {
			if (!key.endsWith("/")) {
				key += "/";
			}
		}
		
		return key;
	}
	

	public MODE getValidateMode() {
		return validateMode;
	}


	public void setValidateMode(MODE validateMode) {
		this.validateMode = validateMode;
	}

}
