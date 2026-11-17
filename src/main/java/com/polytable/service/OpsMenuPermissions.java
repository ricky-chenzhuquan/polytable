package com.polytable.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class OpsMenuPermissions {

    public static final String DRAMA_DATA = "drama-data";
    public static final String CLIPS = "clips";
    public static final String ACCOUNTS = "accounts";
    public static final String SCRIPTS = "scripts";
    public static final String RULES = "rules";
    private static final Set<String> ASSIGNABLE = Set.of(DRAMA_DATA, CLIPS, ACCOUNTS);
    private static final List<String> ORDER = List.of(DRAMA_DATA, CLIPS, ACCOUNTS);

    private OpsMenuPermissions() {
    }

    public static List<String> normalize(Collection<String> requested) {
        Set<String> normalized = new LinkedHashSet<>();
        if (requested != null) {
            for (String item : requested) {
                String value = item == null ? "" : item.trim().toLowerCase(Locale.ROOT);
                if (ASSIGNABLE.contains(value)) {
                    normalized.add(value);
                }
            }
        }
        List<String> result = new ArrayList<>();
        for (String item : ORDER) {
            if (normalized.contains(item)) result.add(item);
        }
        return List.copyOf(result);
    }

    public static boolean canAccess(String role, Collection<String> permissions, String requested) {
        String normalizedRole = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (OpsAuthService.ROLE_ADMIN.equals(normalizedRole)) return true;
        String menu = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        return ASSIGNABLE.contains(menu) && normalize(permissions).contains(menu);
    }
}
