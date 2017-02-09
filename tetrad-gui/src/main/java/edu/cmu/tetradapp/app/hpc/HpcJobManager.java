package edu.cmu.tetradapp.app.hpc;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import edu.cmu.tetradapp.app.TetradDesktop;
import edu.cmu.tetradapp.editor.GeneralAlgorithmEditor;
import edu.cmu.tetradapp.util.DesktopController;
import edu.pitt.dbmi.ccd.rest.client.dto.algo.JobInfo;
import edu.pitt.dbmi.ccd.rest.client.service.jobqueue.JobQueueService;
import edu.pitt.dbmi.tetrad.db.entity.HpcAccount;
import edu.pitt.dbmi.tetrad.db.entity.HpcJobInfo;
import edu.pitt.dbmi.tetrad.db.entity.HpcJobLog;
import edu.pitt.dbmi.tetrad.db.entity.HpcJobLogDetail;
import edu.pitt.dbmi.tetrad.db.service.HpcJobInfoService;
import edu.pitt.dbmi.tetrad.db.service.HpcJobLogService;
import edu.pitt.dbmi.tetrad.db.service.HpcJobLogDetailService;

/**
 * 
 * Dec 14, 2016 3:49:31 PM
 * 
 * @author Chirayu (Kong) Wongchokprasitti, PhD
 * 
 */
public class HpcJobManager {

    private final HpcJobLogService hpcJobLogService;

    private final HpcJobLogDetailService hpcJobLogDetailService;

    private final HpcJobInfoService hpcJobInfoService;

    private final int simultaneousUpload;

    private final ExecutorService executorService;

    private final Timer timer;

    private int TIME_INTERVAL = 10000;

    private final Map<String, Integer> uploadFileProgressMap;

    private final Map<HpcJobInfo, GeneralAlgorithmEditor> hpcGraphResultMap;

    private final Map<HpcAccount, Set<HpcJobInfo>> pendingHpcJobInfoMap;

    private final Map<HpcAccount, Set<HpcJobInfo>> submittedHpcJobInfoMap;

    private final Map<HpcAccount, HpcAccountService> hpcAccountServiceMap;

    public HpcJobManager(final org.hibernate.Session session,
	    final int simultaneousUpload) {
	this.hpcJobLogService = new HpcJobLogService(session);
	this.hpcJobLogDetailService = new HpcJobLogDetailService(session);
	this.hpcJobInfoService = new HpcJobInfoService(session);
	this.simultaneousUpload = simultaneousUpload;

	executorService = Executors.newFixedThreadPool(simultaneousUpload);

	uploadFileProgressMap = new HashMap<>();
	pendingHpcJobInfoMap = new HashMap<>();
	submittedHpcJobInfoMap = new HashMap<>();
	hpcGraphResultMap = new HashMap<>();
	hpcAccountServiceMap = new HashMap<>();

	resumePreProcessJobs();
	resumeSubmittedHpcJobInfos();

	this.timer = new Timer();

	startHpcJobScheduler();
    }

    public Map<HpcAccount, Set<HpcJobInfo>> getPendingHpcJobInfoMap() {
	return pendingHpcJobInfoMap;
    }

    private void resumePreProcessJobs() {
	// Lookup on DB for HpcJobInfo with status -1 (Pending)

	List<HpcJobInfo> pendingHpcJobInfo = hpcJobInfoService.findByStatus(-1);
	if (pendingHpcJobInfo != null) {
	    for (HpcJobInfo hpcJobInfo : pendingHpcJobInfo) {
		System.out.println("resumePreProcessJobs: "
			+ hpcJobInfo.getAlgorithmName()
			+ " : "
			+ hpcJobInfo.getHpcAccount().getConnectionName()
			+ " : "
			+ hpcJobInfo.getAlgorithmParamRequest()
				.getDatasetPath());

		final HpcAccount hpcAccount = hpcJobInfo.getHpcAccount();

		Set<HpcJobInfo> hpcJobInfos = pendingHpcJobInfoMap
			.get(hpcAccount);
		if (hpcJobInfos == null) {
		    hpcJobInfos = new LinkedHashSet<>();
		}
		hpcJobInfos.add(hpcJobInfo);
		pendingHpcJobInfoMap.put(hpcAccount, hpcJobInfos);

		HpcJobPreProcessTask preProcessTask = new HpcJobPreProcessTask(
			hpcJobInfo);
		executorService.submit(preProcessTask);
	    }
	} else {
	    System.out
		    .println("resumePreProcessJobs: no pending jobs to be resumed");
	}
    }

    public void startHpcJobScheduler() {
	System.out.println("startHpcJobScheduler");
	HpcJobsScheduledTask hpcScheduledTask = new HpcJobsScheduledTask();
	timer.schedule(hpcScheduledTask, 1000, TIME_INTERVAL);
    }

    public void submitNewHpcJobToQueue(final HpcJobInfo hpcJobInfo,
	    final GeneralAlgorithmEditor generalAlgorithmEditor) {

	hpcJobInfoService.add(hpcJobInfo);
	System.out.println("hpcJobInfo: id: " + hpcJobInfo.getId());

	HpcJobLog hpcJobLog = new HpcJobLog();
	hpcJobLog.setAddedTime(new Date(System.currentTimeMillis()));
	hpcJobLog.setHpcJobInfo(hpcJobInfo);
	hpcJobLogService.update(hpcJobLog);
	System.out.println("HpcJobLog: id: " + hpcJobLog.getId());

	HpcJobLogDetail hpcJobLogDetail = new HpcJobLogDetail();
	hpcJobLogDetail.setAddedTime(new Date());
	hpcJobLogDetail.setHpcJobLog(hpcJobLog);
	hpcJobLogDetail.setJobState(-1);// Pending
	hpcJobLogDetail.setProgress("Pending");
	hpcJobLogDetailService.add(hpcJobLogDetail);
	System.out.println("HpcJobLogDetail: id: " + hpcJobLogDetail.getId());

	hpcGraphResultMap.put(hpcJobInfo, generalAlgorithmEditor);

	// Put a new pre-process task into hpc job queue
	HpcJobPreProcessTask preProcessTask = new HpcJobPreProcessTask(
		hpcJobInfo);

	// Added a job to the pending list
	final HpcAccount hpcAccount = hpcJobInfo.getHpcAccount();

	Set<HpcJobInfo> hpcJobInfos = pendingHpcJobInfoMap.get(hpcAccount);
	if (hpcJobInfos == null) {
	    hpcJobInfos = new LinkedHashSet<>();
	}
	hpcJobInfos.add(hpcJobInfo);
	pendingHpcJobInfoMap.put(hpcAccount, hpcJobInfos);

	executorService.submit(preProcessTask);

	// (new HpcJobActivityAction("")).actionPerformed(null);
    }

    public void stopHpcJobScheduler() {
	timer.cancel();
    }

    public void restartHpcJobScheduler() {
	stopHpcJobScheduler();
	startHpcJobScheduler();
    }

    public HpcJobLog getHpcJobLog(final HpcJobInfo hpcJobInfo) {
	return hpcJobLogService.findByHpcJobInfo(hpcJobInfo);
    }

    public void appendHpcJobLogDetail(final HpcJobLogDetail hpcJobLogDetail) {
	hpcJobLogDetailService.add(hpcJobLogDetail);
    }

    public HpcJobInfo findHpcJobInfoById(final long id) {
	return hpcJobInfoService.findById(id);
    }

    public void updateHpcJobInfo(final HpcJobInfo hpcJobInfo) {
	hpcJobInfoService.update(hpcJobInfo);
	updateSubmittedHpcJobInfo(hpcJobInfo);
    }

    public void updateHpcJobLog(final HpcJobLog hpcJobLog) {
	hpcJobLogService.update(hpcJobLog);
    }

    public void logHpcJobLogDetail(final HpcJobLog hpcJobLog, int jobStatus,
	    String jobProgress) {
	Date now = new Date(System.currentTimeMillis());
	hpcJobLog.setLastUpdatedTime(now);
	if (jobStatus == 3) {// Finished
	    hpcJobLog.setEndedTime(now);
	}
	if (jobStatus == 4) {// Killed
	    hpcJobLog.setCanceledTime(now);
	}
	updateHpcJobLog(hpcJobLog);

	HpcJobLogDetail hpcJobLogDetail = new HpcJobLogDetail();
	hpcJobLogDetail.setAddedTime(new Date(System.currentTimeMillis()));
	hpcJobLogDetail.setHpcJobLog(hpcJobLog);
	hpcJobLogDetail.setJobState(jobStatus);
	hpcJobLogDetail.setProgress(jobProgress);
	appendHpcJobLogDetail(hpcJobLogDetail);
    }

    public void updateUploadFileProgress(final String datasetPath,
	    int percentageProgress) {
	uploadFileProgressMap.put(datasetPath, percentageProgress);
    }

    public int getUploadFileProgress(final String dataPath) {
	int progress = -1;
	if (uploadFileProgressMap.containsKey(dataPath)) {
	    progress = uploadFileProgressMap.get(dataPath).intValue();
	}
	return progress;
    }

    public void resumeSubmittedHpcJobInfos() {
	// Lookup on DB for HpcJobInfo with status 0 (Submitted); 1 (Running); 2
	// (Kill Request)
	for (int status = 0; status <= 2; status++) {
	    System.out.println("resumeSubmittedHpcJobInfos: "
		    + "looping status: " + status);
	    List<HpcJobInfo> submittedHpcJobInfo = hpcJobInfoService
		    .findByStatus(status);
	    if (submittedHpcJobInfo != null) {
		for (HpcJobInfo hpcJobInfo : submittedHpcJobInfo) {
		    addNewSubmittedHpcJob(hpcJobInfo);
		}
	    }
	}
    }

    public GeneralAlgorithmEditor getGeneralAlgorithmEditor(
	    final HpcJobInfo hpcJobInfo) {
	return hpcGraphResultMap.get(hpcJobInfo);
    }

    public void addNewSubmittedHpcJob(final HpcJobInfo hpcJobInfo) {
	HpcAccount hpcAccount = hpcJobInfo.getHpcAccount();
	System.out.println("addNewSubmittedHpcJob: connection: "
		+ hpcAccount.getConnectionName());
	System.out.println("addNewSubmittedHpcJob: algorithm: "
		+ hpcJobInfo.getAlgorithmName());
	System.out.println("addNewSubmittedHpcJob: status: "
		+ hpcJobInfo.getStatus());
	System.out.println("addNewSubmittedHpcJob: " + "pid: "
		+ hpcJobInfo.getPid());

	Set<HpcJobInfo> hpcJobInfos = submittedHpcJobInfoMap.get(hpcAccount);
	if (hpcJobInfos == null) {
	    hpcJobInfos = new LinkedHashSet<>();
	}
	hpcJobInfos.add(hpcJobInfo);
	submittedHpcJobInfoMap.put(hpcAccount, hpcJobInfos);

	removedPendingHpcJob(hpcJobInfo);
    }

    public void removedFinishedHpcJob(final HpcJobInfo hpcJobInfo) {
	HpcAccount hpcAccount = hpcJobInfo.getHpcAccount();
	System.out.println("removedFinishedHpcJob: connection: "
		+ hpcAccount.getConnectionName());
	System.out.println("removedFinishedHpcJob: algorithm: "
		+ hpcJobInfo.getAlgorithmName());
	System.out.println("removedFinishedHpcJob: status: "
		+ hpcJobInfo.getStatus());
	System.out
		.println("removedFinishedHpcJob: pid: " + hpcJobInfo.getPid());
	Set<HpcJobInfo> hpcJobInfos = submittedHpcJobInfoMap.get(hpcAccount);
	if (hpcJobInfos != null) {
	    hpcJobInfos.remove(hpcJobInfo);
	    if (hpcJobInfos.isEmpty()) {
		submittedHpcJobInfoMap.remove(hpcAccount);
	    } else {
		submittedHpcJobInfoMap.put(hpcAccount, hpcJobInfos);
	    }
	}
	hpcGraphResultMap.remove(hpcJobInfo);
    }

    public void removedPendingHpcJob(final HpcJobInfo hpcJobInfo) {
	HpcAccount hpcAccount = hpcJobInfo.getHpcAccount();
	System.out.println("removedPendingHpcJob: connection: "
		+ hpcAccount.getConnectionName());
	System.out.println("removedPendingHpcJob: algorithm: "
		+ hpcJobInfo.getAlgorithmName());
	System.out.println("removedPendingHpcJob: status: "
		+ hpcJobInfo.getStatus());
	System.out.println("removedPendingHpcJob: pid: " + hpcJobInfo.getPid());
	Set<HpcJobInfo> hpcJobInfos = pendingHpcJobInfoMap.get(hpcAccount);
	if (hpcJobInfos != null) {
	    hpcJobInfos.remove(hpcJobInfo);
	    if (hpcJobInfos.isEmpty()) {
		pendingHpcJobInfoMap.remove(hpcAccount);
	    } else {
		pendingHpcJobInfoMap.put(hpcAccount, hpcJobInfos);
	    }
	}
    }

    public Map<HpcAccount, Set<HpcJobInfo>> getSubmittedHpcJobInfoMap() {
	return submittedHpcJobInfoMap;
    }

    public void updateSubmittedHpcJobInfo(final HpcJobInfo hpcJobInfo) {
	final HpcAccount hpcAccount = hpcJobInfo.getHpcAccount();
	Set<HpcJobInfo> hpcJobInfos = submittedHpcJobInfoMap.get(hpcAccount);
	if (hpcJobInfos != null) {
	    hpcJobInfos.add(hpcJobInfo);
	    submittedHpcJobInfoMap.put(hpcAccount, hpcJobInfos);
	}
    }

    public HpcAccountService getHpcAccountService(final HpcAccount hpcAccount)
	    throws Exception {
	HpcAccountService hpcAccountService = hpcAccountServiceMap
		.get(hpcAccount);
	if (hpcAccountService == null) {
	    hpcAccountService = new HpcAccountService(hpcAccount,
		    simultaneousUpload);
	    hpcAccountServiceMap.put(hpcAccount, hpcAccountService);
	}
	return hpcAccountService;
    }

    public void removeHpcAccountService(final HpcAccount hpcAccount) {
	hpcAccountServiceMap.remove(hpcAccount);
    }

    public Map<HpcAccount, Set<HpcJobInfo>> getFinishedHpcJobInfoMap() {
	final Map<HpcAccount, Set<HpcJobInfo>> finishedHpcJobInfoMap = new HashMap<>();
	// Lookup on DB for HpcJobInfo with status 3 (Finished); 4 (Killed);
	// 5 (Result Downloaded); 6 (Error Result Downloaded);
	for (int status = 3; status <= 6; status++) {
	    System.out.println("getFinishedHpcJobInfoMap: "
		    + "looping status: " + status);
	    List<HpcJobInfo> finishedHpcJobInfo = hpcJobInfoService
		    .findByStatus(status);
	    if (finishedHpcJobInfo != null) {
		for (HpcJobInfo hpcJobInfo : finishedHpcJobInfo) {
		    final HpcAccount hpcAccount = hpcJobInfo.getHpcAccount();
		    Set<HpcJobInfo> hpcJobInfos = finishedHpcJobInfoMap
			    .get(hpcAccount);
		    if (hpcJobInfos == null) {
			hpcJobInfos = new LinkedHashSet<>();
		    }
		    hpcJobInfos.add(hpcJobInfo);
		    finishedHpcJobInfoMap.put(hpcAccount, hpcJobInfos);
		}
	    }
	}
	return finishedHpcJobInfoMap;
    }

    public HpcJobInfo requestHpcJobKilled(final HpcJobInfo hpcJobInfo)
	    throws Exception {
	final HpcAccount hpcAccount = hpcJobInfo.getHpcAccount();

	HpcAccountService hpcAccountService = getHpcAccountService(hpcAccount);

	JobQueueService jobQueueService = hpcAccountService
		.getJobQueueService();
	TetradDesktop desktop = (TetradDesktop) DesktopController.getInstance();
	final HpcAccountManager hpcAccountManager = desktop
		.getHpcAccountManager();
	JsonWebTokenManager jsonWebTokenManager = hpcAccountManager
		.getJsonWebTokenManager();
	jobQueueService.requestJobKilled(hpcJobInfo.getPid(),
		jsonWebTokenManager.getJsonWebToken(hpcAccount));
	JobInfo jobInfo = jobQueueService.getJobStatus(hpcJobInfo.getPid(),
		jsonWebTokenManager.getJsonWebToken(hpcAccount));

	if (jobInfo != null) {
	    hpcJobInfo.setStatus(jobInfo.getStatus());
	    return hpcJobInfo;
	}

	return null;

    }

}
