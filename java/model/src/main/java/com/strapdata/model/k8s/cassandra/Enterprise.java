
package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Enterprise {

    @SerializedName("enabled")
    @Expose
    private Boolean enabled = false;
    @SerializedName("jmx")
    @Expose
    private Boolean jmx = false;
    @SerializedName("https")
    @Expose
    private Boolean https = false;
    @SerializedName("ssl")
    @Expose
    private Boolean ssl = false;
    @SerializedName("aaa")
    @Expose
    private Aaa aaa;
    @SerializedName("cbs")
    @Expose
    private Boolean cbs = false;
}