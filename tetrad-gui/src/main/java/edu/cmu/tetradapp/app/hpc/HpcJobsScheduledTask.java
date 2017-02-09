package edu.cmu.tetradapp.app.hpc;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;

import edu.cmu.tetradapp.app.TetradDesktop;
import edu.cmu.tetradapp.editor.GeneralAlgorithmEditor;
import edu.cmu.tetradapp.util.DesktopController;
import edu.pitt.dbmi.ccd.rest.client.dto.algo.JobInfo;
import edu.pitt.dbmi.ccd.rest.client.dto.algo.ResultFile;
import edu.pitt.dbmi.ccd.rest.client.service.jobqueue.JobQueueService;
import edu.pitt.dbmi.ccd.rest.client.service.result.ResultService;
import edu.pitt.dbmi.tetrad.db.entity.HpcAccount;
import edu.pitt.dbmi.tetrad.db.entity.HpcJobInfo;
import edu.pitt.dbmi.tetrad.db.entity.HpcJobLog;

/**
 * 
 * Jan 10, 2017 11:37:53 AM
 * 
 * @author Chirayu (Kong) Wongchokprasitti, PhD
 * 
 */
public class HpcJobsScheduledTask extends TimerTask {

    public HpcJobsScheduledTask() {
    }

    // Pooling job status from HPC nodes
    @Override
    public void run() {
	TetradDesktop desktop = (TetradDesktop) DesktopController.getInstance();
	while (desktop == null) {
	    try {
		Thread.sleep(1000);
	    } catch (InterruptedException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	    }
	}
	final HpcAccountManager hpcAccountManager = desktop
		.getHpcAccountManager();
	final HpcJobManager hpcJobManager = desktop.getHpcJobManager();

	System.out.println("HpcJobsScheduledTask: "
		+ new Date(System.currentTimeMillis()));

	// Load active jobs: Status (0 = Submitted; 1 = Running; 2 = Kill
	// Request)
	Map<HpcAccount, Set<HpcJobInfo>> submittedHpcJobInfos = hpcJobManager
		.getSubmittedHpcJobInfoMap();
	if (submittedHpcJobInfos.size() == 0) {
	    System.out.println("Active job pool is empty!");
	} else {
	    System.out.println("Active job pool has "
		    + submittedHpcJobInfos.keySet().size() + " hpcAccount"
		    + (submittedHpcJobInfos.keySet().size() > 1 ? "s" : ""));
	}

	for (HpcAccount hpcAccount : submittedHpcJobInfos.keySet()) {

	    System.out.println("HpcJobsScheduledTask: "
		    + hpcAccount.getConnectionName());

	    Set<HpcJobInfo> hpcJobInfos = submittedHpcJobInfos.get(hpcAccount);
	    Map<Long, HpcJobInfo> hpcJobInfoMap = new HashMap<>();
	    for (HpcJobInfo hpcJobInfo : hpcJobInfos) {
		if (hpcJobInfo.getPid() != null) {
		    long pid = hpcJobInfo.getPid().longValue();
		    hpcJobInfoMap.put(pid, hpcJobInfo);

		    System.out.println("id: " + hpcJobInfo.getId() + " : "
			    + hpcJobInfo.getAlgorithmName() + ": pid: " + pid
			    + " : " + hpcJobInfo.getResultFileName());

		} else {

		    System.out.println("id: " + hpcJobInfo.getId() + " : "
			    + hpcJobInfo.getAlgorithmName() + ": no pid! : "
			    + hpcJobInfo.getResultFileName());

		    hpcJobInfos.remove(hpcJobInfo);
		}
	    }

	    try {
		HpcAccountService hpcAccountService = hpcJobManager
			.getHpcAccountService(hpcAccount);

		JobQueueService jobQueueService = hpcAccountService
			.getJobQueueService();
		List<JobInfo> jobInfos = jobQueueService
			.getActiveJobs(HpcAccountUtils.getJsonWebToken(
				hpcAccountManager, hpcAccount));

		for (JobInfo jobInfo : jobInfos) {
		    System.out.println("Remote pid: " + jobInfo.getId() + " : "
			    + jobInfo.getAlgorithmName() + " : "
			    + jobInfo.getResultFileName());

		    long pid = jobInfo.getId();
		    int remoteStatus = jobInfo.getStatus();
		    String recentStatusText = (remoteStatus == 0 ? "Submitted"
			    : (remoteStatus == 1 ? "Running" : "Kill Request"));
		    HpcJobInfo hpcJobInfo = hpcJobInfoMap.get(pid);// Local job
								   // map
		    HpcJobLog hpcJobLog = hpcJobManager
			    .getHpcJobLog(hpcJobInfo);
		    if (hpcJobInfo != null) {
			int status = hpcJobInfo.getStatus();
			if (status != remoteStatus) {
			    // Update status
			    hpcJobInfo.setStatus(remoteStatus);

			    hpcJobManager.updateHpcJobInfo(hpcJobInfo);
			    hpcJobLog.setLastUpdatedTime(new Date(System
				    .currentTimeMillis()));

			    String log = "Job status changed to "
				    + recentStatusText;
			    System.out.println(hpcJobInfo.getAlgorithmName()
				    + " : id : " + hpcJobInfo.getId()
				    + " : pid : " + pid);
			    System.out.println(log);

			    hpcJobManager.logHpcJobLogDetail(hpcJobLog,
				    remoteStatus, log);

			    hpcJobInfos.remove(hpcJobInfo);
			    hpcJobInfoMap.remove(pid);
			}
		    }
		}

		// Download finished jobs' results
		if (hpcJobInfos.size() > 0) {
		    ResultService resultService = hpcAccountService
			    .getResultService();

		    Set<ResultFile> resultFiles = resultService
			    .listAlgorithmResultFiles(HpcAccountUtils
				    .getJsonWebToken(hpcAccountManager,
					    hpcAccount));

		    Set<String> resultFileNames = new HashSet<>();
		    for (ResultFile resultFile : resultFiles) {
			resultFileNames.add(resultFile.getName());
			System.out.println(hpcAccount.getConnectionName()
				+ " Result : " + resultFile.getName());
		    }

		    for (HpcJobInfo hpcJobInfo : hpcJobInfos) {// Job is done or
							       // killed or
							       // time-out
			HpcJobLog hpcJobLog = hpcJobManager
				.getHpcJobLog(hpcJobInfo);
			String recentStatusText = "Job finished";
			int recentStatus = 3; // Finished
			if (hpcJobInfo.getStatus() == 2) {
			    recentStatusText = "Job killed";
			    recentStatus = 4; // Killed
			}
			hpcJobInfo.setStatus(recentStatus);
			hpcJobManager.updateHpcJobInfo(hpcJobInfo);
			System.out.println("hpcJobInfo: id: "
				+ hpcJobInfo.getId() + " : "
				+ hpcJobInfo.getStatus());
			hpcJobManager.logHpcJobLogDetail(hpcJobLog,
				recentStatus, recentStatusText);

			System.out.println(hpcJobInfo.getAlgorithmName()
				+ " : id : " + hpcJobInfo.getId() + " : "
				+ recentStatusText);

			GeneralAlgorithmEditor editor = hpcJobManager
				.getGeneralAlgorithmEditor(hpcJobInfo);
			if (editor != null) {
			    System.out
				    .println("GeneralAlgorithmEditor is not null");
			    String resultJsonFileName = hpcJobInfo
				    .getResultJsonFileName();
			    String errorResultFileName = hpcJobInfo
				    .getErrorResultFileName();

			    if (resultFileNames.contains(resultJsonFileName)) {
				recentStatus = 5; // Result Downloaded
				String json = resultService
					.downloadAlgorithmResultFile(
						resultJsonFileName,
						HpcAccountUtils
							.getJsonWebToken(
								hpcAccountManager,
								hpcAccount));

				String log = "Result downloaded";
				hpcJobManager.logHpcJobLogDetail(hpcJobLog,
					recentStatus, log);

				System.out.println(hpcJobInfo
					.getAlgorithmName()
					+ " : id : "
					+ hpcJobInfo.getId() + " : " + log);

				editor.setAlgorithmResult(json);
			    } else if (resultFileNames
				    .contains(errorResultFileName)) {
				recentStatus = 6; // Error Result Downloaded
				String error = resultService
					.downloadAlgorithmResultFile(
						errorResultFileName,
						HpcAccountUtils
							.getJsonWebToken(
								hpcAccountManager,
								hpcAccount));

				String log = "Error Result downloaded";
				hpcJobManager.logHpcJobLogDetail(hpcJobLog,
					recentStatus, log);

				System.out.println(hpcJobInfo
					.getAlgorithmName()
					+ " : id : "
					+ hpcJobInfo.getId() + " : " + log);

				editor.setAlgorithmErrorResult(error);
			    } else {

				Thread.sleep(5000);

				// Try again
				String json = resultService
					.downloadAlgorithmResultFile(
						resultJsonFileName,
						HpcAccountUtils
							.getJsonWebToken(
								hpcAccountManager,
								hpcAccount));
				System.out.println("json: \n" + json);

				String error = resultService
					.downloadAlgorithmResultFile(
						errorResultFileName,
						HpcAccountUtils
							.getJsonWebToken(
								hpcAccountManager,
								hpcAccount));
				System.out.println("error: \n" + error);

				if (!json.toLowerCase().contains("not found")) {
				    recentStatus = 5; // Result Downloaded

				    String log = "Result downloaded";
				    hpcJobManager.logHpcJobLogDetail(hpcJobLog,
					    recentStatus, log);

				    System.out.println(hpcJobInfo
					    .getAlgorithmName()
					    + " : id : "
					    + hpcJobInfo.getId() + " : " + log);

				    editor.setAlgorithmResult(json);
				}
				if (!error.toLowerCase().contains("not found")) {
				    recentStatus = 6; // Error Result Downloaded

				    String log = "Error Result downloaded";
				    hpcJobManager.logHpcJobLogDetail(hpcJobLog,
					    recentStatus, log);

				    System.out.println(hpcJobInfo
					    .getAlgorithmName()
					    + " : id : "
					    + hpcJobInfo.getId() + " : " + log);

				    editor.setAlgorithmErrorResult(error);

				} else {
				    recentStatus = 7; // Result Not Found
				    String log = resultJsonFileName
					    + " not found";
				    hpcJobManager.logHpcJobLogDetail(hpcJobLog,
					    recentStatus, log);

				    System.out.println(hpcJobInfo
					    .getAlgorithmName()
					    + " : id : "
					    + hpcJobInfo.getId() + " : " + log);
				}

			    }

			}
			hpcJobManager.removedFinishedHpcJob(hpcJobInfo);
		    }
		} else {
		    System.out.println("Active HpcJobInfo is empty.");
		}

	    } catch (Exception e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	    }

	}

    }

}
