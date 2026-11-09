package com.pico4.regionswitch;

import org.json.*;

/** Fixed multi-package session grammar; no user-supplied shell input. */
public final class InstallPlan {
    public static String create(JSONObject profile) throws Exception {
        StringBuilder code = new StringBuilder("#!/system/bin/sh\nset -eu\nparent=\nchildren=\ncleanup() {\n"
            + "  [ -z \"$parent\" ] || pm install-abandon \"$parent\" >/dev/null 2>&1 || true\n"
            + "  for child in $children; do pm install-abandon \"$child\" >/dev/null 2>&1 || true; done\n}\ntrap cleanup EXIT\n"
            + "pm_ok() {\n  reply=$(pm \"$@\") || { echo \"$reply\"; return 1; }\n  echo \"$reply\"\n"
            + "  if [ \"$1\" = \"install-write\" ]; then\n    [ \"$reply\" = \"Success: streamed $3 bytes\" ]\n"
            + "  else\n    [ \"$reply\" = \"Success\" ]\n  fi\n}\n"
            + "create() {\n  reply=$(pm install-create \"$@\") || { echo \"$reply\"; exit 1; }\n  echo \"$reply\" >&2\n"
            + "  id=$(printf \"%s\\n\" \"$reply\" | sed -n \"s/^Success: created install session \\[\\([0-9][0-9]*\\)\\].*/\\1/p\")\n"
            + "  case \"$id\" in \"\"|*[!0-9]*) exit 1;; esac\n  echo \"$id\"\n}\n"
            + "parent=$(create --multi-package -r -d --user 0)\n");
        for (int i = 0; i < Engine.ROLES.length; i++) {
            JSONObject apk = profile.getJSONObject("apks").getJSONObject(Engine.ROLES[i]);
            if (!apk.getString("package").equals(Engine.PACKAGES[i])) throw new IllegalArgumentException("Unexpected package");
            long size = apk.getLong("size");
            code.append("child=$(create -r -d --user 0 -S ").append(size).append(")\nchildren=\"$children $child\"\n")
                .append("pm_ok install-write -S ").append(size).append(" \"$child\" base.apk ").append(Engine.q(apk.getString("path")))
                .append("\npm_ok install-add-session \"$parent\" \"$child\"\n");
        }
        return code + "pm_ok install-commit \"$parent\"\nparent=\nchildren=\necho REGION_ATOMIC_INSTALL_SUCCESS\n";
    }
    private InstallPlan() {}
}
