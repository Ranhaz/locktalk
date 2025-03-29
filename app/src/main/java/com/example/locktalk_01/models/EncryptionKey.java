
package com.example.locktalk_01.models;

public class EncryptionKey {
    private String contactName;
    private String sharedKey;

    public EncryptionKey(String contactName, String sharedKey) {
        this.contactName = contactName;
        this.sharedKey = sharedKey;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getSharedKey() {
        return sharedKey;
    }

    public void setSharedKey(String sharedKey) {
        this.sharedKey = sharedKey;
    }
}
