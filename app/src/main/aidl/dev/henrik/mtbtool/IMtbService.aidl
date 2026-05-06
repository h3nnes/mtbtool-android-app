// IMtbService.aidl
package dev.henrik.mtbtool;

interface IMtbService {
    int execMtb(in String[] args);
    /** Runs mtb and returns combined stdout+stderr output. exitCode is in the first line as "EXIT:<n>\n". */
    String execMtbWithOutput(in String[] args);
    void destroy();
}
