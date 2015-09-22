package org.sample;

import org.tigris.subversion.svnclientadapter.*;
import org.tigris.subversion.svnclientadapter.commandline.CmdLineClientAdapter;
import org.tigris.subversion.svnclientadapter.commandline.CmdLineClientAdapterFactory;
import org.tigris.subversion.svnclientadapter.svnkit.SvnKitClientAdapterFactory;
import org.tigris.subversion.svnclientadapter.utils.Depth;

import java.io.File;
import java.io.FileFilter;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

public class Client {

    private static final int UNVERSIONED = SVNStatusKind.UNVERSIONED.toInt();
    private static final int MISSING = SVNStatusKind.MISSING.toInt();
    private static final boolean RECURSIVE = true;
    private ISVNClientAdapter svnClient;
    private String url;

    public Client(String user, String password, String url, String clientType) throws SVNClientException {
        this.url = url;
        if ("svnkit".equals(clientType)) {
            System.out.println("Using SVN-KIT");
            SvnKitClientAdapterFactory.setup();
        } else {
            System.out.println("Using CMD-LINE");
            CmdLineClientAdapterFactory.setup();
        }
        svnClient = SVNClientAdapterFactory.createSVNClient(SVNClientAdapterFactory.getPreferredSVNClientType());
        if (user != null) {
            svnClient.setUsername(user);
            svnClient.setPassword(password);
        }
    }


    public void commit(String filePath) {
        System.out.println("SVN checking in " + filePath);

        File root = new File(filePath);
        try {
            svnClient.cleanup(root);
            // need to check svn status before executing other operations on commit logic otherwise unnecessary
            // getStatus() will get called
            ISVNStatus[] checkStatus = svnClient.getStatus(root, true, false);
            if (checkStatus != null && checkStatus.length > 0) {
                svnAddFiles(root, checkStatus);
                cleanupDeletedFiles(checkStatus);

                ISVNStatus[] status = svnClient.getStatus(root, true, false);
                if (status != null && status.length > 0 && !isAllUnversioned(status)) {
                    File[] files = new File[]{root};
                    svnClient.commit(files, "Commit initiated by deployment synchronizer", true);
                    System.out.println("SVN checked in successfully" + filePath);

                    //Always do a svn update if you do a commit. This is just to update the working copy's
                    //revision number to the latest. This fixes out-of-date working copy issues.

                    System.out.println("Updating the working copy after the commit.");
                    checkout(filePath);
                    System.out.println("Updated the working copy after the commit.");
                } else {
                    System.out.println("No changes in the local working copy");
                }
            } else {
                System.out.println("No changes in the local working copy to  commit " + filePath);
            }
        } catch (SVNClientException e) {
            e.printStackTrace();
            System.out.println("Error while committing artifacts to the SVN repository" + e.getMessage());

        }
    }

    private void svnAddFiles(File root, ISVNStatus[] checkStatus) throws SVNClientException {
        System.out.println("SVN adding files in " + root);
        for (ISVNStatus s : checkStatus) {
            if (s.getTextStatus().toInt() == UNVERSIONED) {
                File file = s.getFile();

                String filePath = file.getPath();
                if (file.isFile()) {
                    System.out.println(" SVN ADD : " + filePath);
                    svnClient.addFile(file);

                } else {
                    // Do not svn add directories with the recursive option.
                    // That will add child directories and files that we don't want to add.
                    // First add the top level directory only.
                    svnClient.addDirectory(file, false);

                    // Then iterate over the children and add each of them by recursively calling
                    // this method
                    File[] children = file.listFiles(new FileFilter() {
                        public boolean accept(File file) {
                            return !file.getName().equals(".svn");
                        }
                    });

                    for (File child : children) {
                        ISVNStatus[] statusChild = svnClient.getStatus(child, true, false);
                        svnAddFiles(child, statusChild);
                    }
                }
            }
        }
    }

    private void cleanupDeletedFiles(ISVNStatus[] status) throws SVNClientException {
        List<File> deletableFiles = new ArrayList<File>();
        for (ISVNStatus s : status) {
            int statusCode = s.getTextStatus().toInt();
            if (statusCode == MISSING) {
                System.out.println("Scheduling the file: " + s.getPath() + " for SVN delete");

                deletableFiles.add(s.getFile());
            }
        }

        if (deletableFiles.size() > 0) {
            svnClient.remove(deletableFiles.toArray(new File[deletableFiles.size()]), true);
        }
    }


    private boolean isAllUnversioned(ISVNStatus[] status) {
        for (ISVNStatus s : status) {
            if (s.getTextStatus().toInt() != UNVERSIONED) {
                return false;
            }
        }
        return true;
    }


    public void checkout(String filePath) {
        System.out.println("SVN checking out " + filePath);

        SVNUrl svnUrl = null;
        try {
            svnUrl = new SVNUrl(url);
        } catch (MalformedURLException e) {
            System.out.println("Provided SVN URL is malformed: " + url);
        }
        File root = new File(filePath);
        try {
            ISVNStatus[] svnStatus = svnClient.getStatus(root, true, false);
            if (svnStatus != null) {
                cleanupDeletedFiles(svnStatus);
            }
            ISVNStatus status = svnClient.getSingleStatus(root);

            if (status != null && status.getTextStatus().toInt() == UNVERSIONED) {
                if (svnClient instanceof CmdLineClientAdapter) {
                    // CmdLineClientAdapter does not support all the options
                    svnClient.checkout(svnUrl, root, SVNRevision.HEAD, RECURSIVE);
                    System.out.println("Checked out using CmdLineClientAdapter");

                } else {
                    svnClient.checkout(svnUrl, root, SVNRevision.HEAD,
                            Depth.infinity, false, true);
                    System.out.println("Checked out using SVN Kit");
                }
                System.out.println("SVN checked out successfully : " + filePath);
            }
        } catch (SVNClientException e) {
            e.printStackTrace();
            System.out.println("Error while checking out or updating artifacts from the " +
                    "SVN repository" + e.getMessage());
        }
    }

    public static void main(String[] args) {
        try {
            String clientType = "svnkit";
            if (args.length > 4 && args[4] != null && !args[4].isEmpty()) {
                clientType = args[4];
            }
            Client client = new Client(args[0], args[1], args[2], clientType);
            while (true) {
                client.checkout(args[3]);
                client.commit(args[3]);
                Thread.sleep(15000);
            }
        } catch (SVNClientException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
