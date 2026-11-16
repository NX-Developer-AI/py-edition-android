package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerStatusFragment extends Fragment {
    public static final String TAG = "PYEDITION_SERVER_STATUS_FRAGMENT";
    private static final String PREFERENCES = "pyedition_server_history";
    private static final String HISTORY_KEY = "entries";
    private static final int DEFAULT_PORT = 25565;
    private static final int TIMEOUT_MILLISECONDS = 10000;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private EditText mAddressEditText;
    private Button mCheckButton;
    private ProgressBar mProgressBar;
    private ImageView mFaviconView;
    private TextView mResultView;
    private TextView mHistoryTitle;
    private LinearLayout mHistoryContainer;

    public ServerStatusFragment() {
        super(R.layout.fragment_server_status);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mAddressEditText = view.findViewById(R.id.server_address);
        mCheckButton = view.findViewById(R.id.server_check);
        mProgressBar = view.findViewById(R.id.server_progress);
        mFaviconView = view.findViewById(R.id.server_favicon);
        mResultView = view.findViewById(R.id.server_result);
        mHistoryTitle = view.findViewById(R.id.server_history_title);
        mHistoryContainer = view.findViewById(R.id.server_history);
        mCheckButton.setOnClickListener(v -> checkServer());
        mAddressEditText.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                checkServer();
                return true;
            }
            return false;
        });
        renderHistory();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    private void checkServer() {
        String address = mAddressEditText.getText().toString().trim();
        if (address.isEmpty()) return;
        String host = address;
        int port = DEFAULT_PORT;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {
            host = address.substring(0, colon);
            try {
                port = Integer.parseInt(address.substring(colon + 1));
            } catch (NumberFormatException exception) {
                port = DEFAULT_PORT;
            }
            if (port <= 0 || port > 65535) port = DEFAULT_PORT;
        }
        mCheckButton.setEnabled(false);
        mProgressBar.setVisibility(View.VISIBLE);
        mFaviconView.setVisibility(View.GONE);
        mResultView.setText("");
        final String finalHost = host;
        final int finalPort = port;
        mExecutor.execute(() -> {
            String text;
            Bitmap favicon = null;
            boolean success = false;
            try {
                JSONObject status = ping(finalHost, finalPort);
                text = describe(status, finalHost, finalPort);
                favicon = decodeFavicon(status.optString("favicon"));
                success = true;
            } catch (UnknownHostException exception) {
                text = getString(R.string.pyedition_server_error_resolve);
            } catch (SocketTimeoutException exception) {
                text = getString(R.string.pyedition_server_error_timeout);
            } catch (IOException exception) {
                text = getString(R.string.pyedition_server_error_connect);
            } catch (Exception exception) {
                text = getString(R.string.pyedition_server_error_response);
            }
            final String resultText = text;
            final Bitmap resultFavicon = favicon;
            final boolean resultSuccess = success;
            mMainHandler.post(() -> {
                if (!isAdded()) return;
                mCheckButton.setEnabled(true);
                mProgressBar.setVisibility(View.GONE);
                mResultView.setText(resultText);
                if (resultFavicon != null) {
                    mFaviconView.setImageBitmap(resultFavicon);
                    mFaviconView.setVisibility(View.VISIBLE);
                }
                if (resultSuccess) remember(address);
            });
        });
    }

    private String describe(JSONObject status, String host, int port) {
        JSONObject version = status.optJSONObject("version");
        JSONObject players = status.optJSONObject("players");
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.pyedition_server_address)).append(": ").append(host).append(':').append(port).append('\n');
        if (version != null) {
            builder.append(getString(R.string.pyedition_server_version)).append(": ").append(plainText(version.opt("name"))).append('\n');
            builder.append(getString(R.string.pyedition_server_protocol)).append(": ").append(version.optInt("protocol", -1)).append('\n');
        }
        if (players != null) {
            builder.append(getString(R.string.pyedition_server_players)).append(": ").append(players.optInt("online")).append(" / ").append(players.optInt("max")).append('\n');
            JSONArray sample = players.optJSONArray("sample");
            if (sample != null && sample.length() > 0) {
                List<String> names = new ArrayList<>();
                for (int index = 0; index < sample.length(); index++) {
                    JSONObject entry = sample.optJSONObject(index);
                    if (entry != null) names.add(plainText(entry.optString("name")));
                }
                builder.append(getString(R.string.pyedition_server_online)).append(": ").append(TextUtils.join(", ", names)).append('\n');
            }
        }
        if (status.has("latency")) {
            builder.append(getString(R.string.pyedition_server_latency)).append(": ").append(status.optLong("latency")).append(" ms\n");
        }
        builder.append('\n').append(plainText(status.opt("description")));
        return builder.toString();
    }

    private static Bitmap decodeFavicon(String favicon) {
        int comma = favicon == null ? -1 : favicon.indexOf(',');
        if (comma < 0) return null;
        try {
            byte[] bytes = Base64.decode(favicon.substring(comma + 1), Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            return bitmap == null ? null : Bitmap.createScaledBitmap(bitmap, 256, 256, false);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String plainText(Object component) {
        StringBuilder builder = new StringBuilder();
        appendText(builder, component);
        return builder.toString().replaceAll("§.", "");
    }

    private static void appendText(StringBuilder builder, Object component) {
        if (component == null || component == JSONObject.NULL) return;
        if (component instanceof String) {
            builder.append((String) component);
        } else if (component instanceof JSONArray) {
            JSONArray array = (JSONArray) component;
            for (int index = 0; index < array.length(); index++) appendText(builder, array.opt(index));
        } else if (component instanceof JSONObject) {
            JSONObject object = (JSONObject) component;
            String text = object.optString("text", "");
            builder.append(text.isEmpty() ? object.optString("translate", "") : text);
            appendText(builder, object.optJSONArray("extra"));
        } else {
            builder.append(component);
        }
    }

    private static JSONObject ping(String host, int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLISECONDS);
            socket.setSoTimeout(TIMEOUT_MILLISECONDS);
            OutputStream output = socket.getOutputStream();
            DataInputStream input = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, -1);
            writeString(handshake, host);
            handshake.write(port >> 8);
            handshake.write(port & 0xFF);
            writeVarInt(handshake, 1);
            output.write(packet(0x00, handshake.toByteArray()));
            output.write(packet(0x00, new byte[0]));
            output.flush();

            int length = readVarInt(input);
            if (length <= 0 || length > 4 * 1024 * 1024) throw new IOException("Invalid status length");
            byte[] body = new byte[length];
            input.readFully(body);
            DataInputStream bodyInput = new DataInputStream(new java.io.ByteArrayInputStream(body));
            if (readVarInt(bodyInput) != 0x00) throw new IllegalStateException("Unexpected packet");
            byte[] json = new byte[readVarInt(bodyInput)];
            bodyInput.readFully(json);
            JSONObject status = new JSONObject(new String(json, StandardCharsets.UTF_8));

            try {
                long sentAt = System.nanoTime();
                long timestamp = System.currentTimeMillis();
                byte[] payload = new byte[8];
                for (int index = 0; index < 8; index++) payload[index] = (byte) (timestamp >> ((7 - index) * 8));
                output.write(packet(0x01, payload));
                output.flush();
                int pongLength = readVarInt(input);
                input.readFully(new byte[pongLength]);
                status.put("latency", (System.nanoTime() - sentAt) / 1_000_000L);
            } catch (IOException exception) {
                status.remove("latency");
            }
            return status;
        }
    }

    private static byte[] packet(int identifier, byte[] payload) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, identifier);
        body.write(payload);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, body.size());
        body.writeTo(packet);
        return packet.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        while (true) {
            if ((remaining & ~0x7F) == 0) {
                output.write(remaining);
                return;
            }
            output.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
    }

    private static void writeString(ByteArrayOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static int readVarInt(InputStream input) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int value = input.read();
            if (value < 0) throw new EOFException();
            result |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) return result;
        }
        throw new IOException("VarInt too long");
    }

    private SharedPreferences preferences() {
        return requireContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private List<String> history() {
        String stored = preferences().getString(HISTORY_KEY, "");
        List<String> entries = new ArrayList<>();
        for (String entry : Arrays.asList(stored.split("\n"))) {
            if (!entry.trim().isEmpty()) entries.add(entry);
        }
        return entries;
    }

    private void remember(String address) {
        List<String> entries = history();
        entries.remove(address);
        entries.add(0, address);
        while (entries.size() > 10) entries.remove(entries.size() - 1);
        preferences().edit().putString(HISTORY_KEY, TextUtils.join("\n", entries)).apply();
        renderHistory();
    }

    private void renderHistory() {
        List<String> entries = history();
        mHistoryContainer.removeAllViews();
        mHistoryTitle.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        int padding = (int) (10 * getResources().getDisplayMetrics().density);
        for (String entry : entries) {
            TextView item = new TextView(requireContext());
            item.setText(entry);
            item.setTextColor(0xFF7FB3E0);
            item.setTextSize(16);
            item.setPadding(0, padding, 0, padding);
            item.setOnClickListener(v -> {
                mAddressEditText.setText(entry);
                checkServer();
            });
            mHistoryContainer.addView(item);
        }
    }
}
