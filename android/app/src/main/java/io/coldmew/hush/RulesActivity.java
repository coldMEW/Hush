package io.coldmew.hush;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

// Editor for rules.txt. The VPN service reloads the file on its next query, so saving applies at once.
public class RulesActivity extends Activity {
    private EditText editor;
    private TextView error;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_rules);
        Insets.apply(this, findViewById(R.id.root));
        editor = findViewById(R.id.editor);
        error = findViewById(R.id.error);
        File f = Rules.file(this);
        String text = Rules.DEFAULTS;
        try { if (f.exists()) text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) {}
        if (b == null) editor.setText(text);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.reset).setOnClickListener(v -> { editor.setText(Rules.DEFAULTS); error.setVisibility(View.GONE); });
        findViewById(R.id.save).setOnClickListener(v -> save());
    }

    private void save() {
        String text = editor.getText().toString();
        String problem = Rules.validate(text);
        if (problem != null) {
            error.setText(getString(R.string.rules_invalid, problem));
            error.setVisibility(View.VISIBLE);
            return;
        }
        error.setVisibility(View.GONE);
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(Rules.file(this)), StandardCharsets.UTF_8)) {
            w.write(text.endsWith("\n") ? text : text + "\n");
            Events.add(this, Events.INFO, "DNS rules updated");
            Toast.makeText(this, R.string.rules_saved, Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, R.string.rules_write_failed, Toast.LENGTH_LONG).show();
        }
    }
}
