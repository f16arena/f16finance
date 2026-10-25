package woyou.aidlservice.jiuiv5;

import woyou.aidlservice.jiuiv5.ICallback;

// Sunmi built-in printer service.
// The transaction code of a call is the POSITION of the method, so every method of
// the original up to the last one we use (printText) is listed in the original order,
// even the unused ones. Reordering or removing any of them calls the wrong method.
interface IWoyouService {
    void updateFirmware();
    int getFirmwareStatus();
    String getServiceVersion();
    void printerInit(in ICallback callback);
    void printerSelfChecking(in ICallback callback);
    String getPrinterSerialNo();
    String getPrinterModal();
    String getPrinterVersion();
    String getPrintedLength();
    void lineWrap(int n, in ICallback callback);
    void sendRAWData(in byte[] data, in ICallback callback);
    void setAlignment(int alignment, in ICallback callback);
    void setFontName(String typeface, in ICallback callback);
    void setFontSize(float fontsize, in ICallback callback);
    void printText(String text, in ICallback callback);
}
