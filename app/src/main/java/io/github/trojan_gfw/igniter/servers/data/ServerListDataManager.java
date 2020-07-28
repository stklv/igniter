package io.github.trojan_gfw.igniter.servers.data;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

import io.github.trojan_gfw.igniter.TrojanConfig;
import io.github.trojan_gfw.igniter.TrojanHelper;
import io.github.trojan_gfw.igniter.TrojanURLHelper;
import io.github.trojan_gfw.igniter.common.constants.ConfigFileConstants;
import io.github.trojan_gfw.igniter.common.utils.DecodeUtils;

public class ServerListDataManager implements ServerListDataSource {
    private final String mConfigFilePath;
    private boolean mProxyOn;
    private String mProxyHost;
    private long mProxyPort;

    public ServerListDataManager(String configFilePath, boolean proxyOn, String proxyHost, long proxyPort) {
        mConfigFilePath = configFilePath;
        mProxyOn = proxyOn;
        mProxyHost = proxyHost;
        mProxyPort = proxyPort;
    }

    @Override
    @NonNull
    public List<TrojanConfig> loadServerConfigList() {
        return new ArrayList<>(TrojanHelper.readTrojanServerConfigList(mConfigFilePath));
    }

    @Override
    public void batchDeleteServerConfigs(Collection<TrojanConfig> configs) {
        Set<String> remoteAddrSet = new HashSet<>();
        for (TrojanConfig config : configs) {
            remoteAddrSet.add(config.getRemoteAddr());
        }
        List<TrojanConfig> trojanConfigs = loadServerConfigList();
        for (int i = trojanConfigs.size() - 1; i >= 0; i--) {
            if (remoteAddrSet.contains(trojanConfigs.get(i).getRemoteAddr())) {
                trojanConfigs.remove(i);
            }
        }
        replaceServerConfigs(trojanConfigs);
    }

    @Override
    public void deleteServerConfig(TrojanConfig config) {
        List<TrojanConfig> trojanConfigs = loadServerConfigList();
        for (int i = trojanConfigs.size() - 1; i >= 0; i--) {
            if (trojanConfigs.get(i).getRemoteAddr().equals(config.getRemoteAddr())) {
                trojanConfigs.remove(i);
                replaceServerConfigs(trojanConfigs);
                break;
            }
        }
    }

    @Override
    public void saveServerConfig(TrojanConfig config) {
        if (config == null) {
            return;
        }
        final String remoteAddr = config.getRemoteAddr();
        if (remoteAddr == null) {
            return;
        }
        boolean configRemoteAddrExists = false;
        List<TrojanConfig> trojanConfigs = loadServerConfigList();
        for (int i = trojanConfigs.size() - 1; i >= 0; i--) {
            TrojanConfig cacheConfig = trojanConfigs.get(i);
            if (cacheConfig == null) continue;
            if (remoteAddr.equals(cacheConfig.getRemoteAddr())) {
                trojanConfigs.set(i, config);
                configRemoteAddrExists = true;
                break;
            }
        }
        if (!configRemoteAddrExists) {
            trojanConfigs.add(config);
        }
        replaceServerConfigs(trojanConfigs);
    }

    @Override
    public void replaceServerConfigs(List<TrojanConfig> list) {
        TrojanHelper.writeTrojanServerConfigList(list, mConfigFilePath);
        TrojanHelper.ShowTrojanConfigList(mConfigFilePath);
    }

    @Override
    public void requestSubscribeServerConfigs(String urlStr, @NonNull Callback callback) {
        URL url;
        try {
            url = new URL(urlStr);
        } catch (MalformedURLException e) {
            e.printStackTrace();
            callback.onFailed();
            return;
        }
        HttpURLConnection connection = null;
        try {
            if (mProxyOn) {
                InetSocketAddress proxyAddress = new InetSocketAddress(mProxyHost, (int) mProxyPort);
                Proxy proxy = new Proxy(Proxy.Type.SOCKS, proxyAddress);
                connection = (HttpURLConnection) url.openConnection(proxy);
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }
            connection.setReadTimeout(10000); // 10s timeout
            connection.setConnectTimeout(10000);
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                callback.onFailed();
                return;
            }
            try (InputStream stream = connection.getInputStream()) {
                if (stream != null) {
                    @Nullable String response = DecodeUtils.decodeBase64(readStringFromStream(stream));
                    if (TextUtils.isEmpty(response)) {
                        callback.onFailed();
                    } else {
                        parseAndSaveSubscribeServers(Objects.requireNonNull(response));
                        callback.onSuccess();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            callback.onFailed();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void parseAndSaveSubscribeServers(@NonNull String configLines) {
        int idxOfLineStart = 0, idxOfSharp = -1, idxOfLineEnd = -1;
        Map<String, TrojanConfig> configMap = new HashMap<>(60);
        for (int i = 0, len = configLines.length(); i < len; i++) {
            char c = configLines.charAt(i);
            if (c == '#') {
                idxOfSharp = i;
            } else if (c == '\n') {
                idxOfLineEnd = i;
                if (idxOfSharp != -1) {
                    String trojanUrl = configLines.substring(idxOfLineStart, idxOfSharp);
                    String remark = configLines.substring(idxOfSharp + 1, idxOfLineEnd).trim();
                    TrojanConfig config = TrojanURLHelper.ParseTrojanURL(trojanUrl);
                    if (config != null) {
                        config.setRemoteServerRemark(remark);
                        configMap.put(config.getRemoteAddr(), config);
                    }
                }
                idxOfLineStart = idxOfLineEnd + 1;
                idxOfSharp = -1;
            }
        }
        List<TrojanConfig> previousList = loadServerConfigList();
        for (int i = 0, size = previousList.size(); i < size; i++) {
            TrojanConfig config = previousList.get(i);
            String remoteAddr = config.getRemoteAddr();
            if (configMap.containsKey(remoteAddr)) {
                previousList.set(i, configMap.remove(remoteAddr));
            }
        }
        Collection<TrojanConfig> remainConfigs = configMap.values();
        if (remainConfigs.size() > 0) {
            // sort the remaining new TrojanConfigs from subscription, and append them to the end
            // of server list.
            List<TrojanConfig> newList = new ArrayList<>(remainConfigs);
            Collections.sort(newList, (a, b) -> b.getRemoteAddr().compareTo(a.getRemoteAddr()));
            previousList.addAll(newList);
        }
        replaceServerConfigs(previousList);
    }

    private String readStringFromStream(InputStream inputStream) {
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            StringBuilder sb = new StringBuilder();
            int readSize;
            while ((readSize = reader.read(buf)) != -1) {
                sb.append(buf, 0, readSize);
            }
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Nullable
    private String readFileContentFromUri(Context context, Uri fileUri) {
        try (InputStream is = context.getContentResolver().openInputStream(fileUri)) {
            return readStringFromStream(is);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public List<TrojanConfig> importServersFromFile(Context context, Uri fileUri) {
        List<TrojanConfig> trojanConfigsFromFile = parseTrojanConfigsFromFileContent(readFileContentFromUri(context, fileUri));
        List<TrojanConfig> currentConfigs = loadServerConfigList();
        final int importedSize = trojanConfigsFromFile.size();
        if (importedSize == 0) return currentConfigs;
        // Used for filtering trojan configs with the same remote url address.
        Map<String, TrojanConfig> currentConfigUrlMap = new HashMap<>();
        for (TrojanConfig config : currentConfigs) {
            currentConfigUrlMap.put(config.getRemoteAddr(), config);
        }
        // Find out the intersection of previous configs and configs to be imported by comparing
        // the remote url address. Replace the previous properties with the new imported ones.
        int overlapCount = 0;
        for (int i = importedSize - 1; i >= 0; i--) {
            TrojanConfig config = trojanConfigsFromFile.get(i);
            TrojanConfig currentIdenticalConfig = currentConfigUrlMap.get(config.getRemoteAddr());
            if (currentIdenticalConfig != null) {
                currentIdenticalConfig.copyFrom(config);
                ++overlapCount;
                Collections.swap(trojanConfigsFromFile, i, importedSize - overlapCount);
            }
        }
        // Append the remaining imported trojan configs at the end of the config list.
        for (int i = 0, end = importedSize - overlapCount; i < end; i++) {
            currentConfigs.add(trojanConfigsFromFile.get(i));
        }
        replaceServerConfigs(currentConfigs);
        return currentConfigs;
    }

    @NonNull
    private List<TrojanConfig> parseTrojanConfigsFromFileContent(String fileContent) {
        try {
            JSONObject jsonObject = new JSONObject(fileContent);
            JSONArray configs = jsonObject.optJSONArray(ConfigFileConstants.CONFIGS);
            if (configs == null) {
                return Collections.emptyList();
            }
            final int len = configs.length();
            List<TrojanConfig> list = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                JSONObject config = configs.getJSONObject(i);
                String remoteAddr = config.optString(ConfigFileConstants.SERVER, null);
                if (remoteAddr == null) {
                    continue;
                }
                TrojanConfig tmp = new TrojanConfig();
                tmp.setRemoteServerRemark(config.optString(ConfigFileConstants.REMARKS, ConfigFileConstants.NO_REMARKS));
                tmp.setRemoteAddr(remoteAddr);
                tmp.setRemotePort(config.optInt(ConfigFileConstants.SERVER_PORT));
                tmp.setPassword(config.optString(ConfigFileConstants.PASSWORD));
                tmp.setVerifyCert(config.optBoolean(ConfigFileConstants.VERIFY));
                list.add(tmp);
            }
            return list;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    @Override
    public boolean exportServers(String exportPath) {
        return TrojanHelper.writeStringToFile(getExportContent(), exportPath);
    }

    private String getExportContent() {
        List<TrojanConfig> trojanConfigs = loadServerConfigList();
        JSONArray array = new JSONArray();
        int index = 0;
        for (TrojanConfig trojanConfig : trojanConfigs) {
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put(ConfigFileConstants.REMARKS, trojanConfig.getRemoteServerRemark());
                jsonObject.put(ConfigFileConstants.SERVER, trojanConfig.getRemoteAddr());
                jsonObject.put(ConfigFileConstants.SERVER_PORT, trojanConfig.getRemotePort());
                jsonObject.put(ConfigFileConstants.PASSWORD, trojanConfig.getPassword());
                jsonObject.put(ConfigFileConstants.VERIFY, trojanConfig.getVerifyCert());
                // for future
                // jsonObject.put("enable_ipv6", trojanConfig.getEnableIpv6());
                // jsonObject.put("enable_clash", trojanConfig.getEnableClash());
                array.put(index++, jsonObject);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put(ConfigFileConstants.CONFIGS, array);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject.toString();
    }
}
