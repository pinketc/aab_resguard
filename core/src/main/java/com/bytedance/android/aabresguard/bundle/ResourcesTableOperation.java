package com.bytedance.android.aabresguard.bundle;

import com.android.aapt.Resources;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by YangJing on 2019/10/10 .
 * Email: yangjing.yeoh@bytedance.com
 */
public class ResourcesTableOperation {

    public static Resources.ConfigValue replaceEntryPath(Resources.ConfigValue configValue, String path) {
        Resources.ConfigValue.Builder entryBuilder = configValue.toBuilder();
        entryBuilder.setValue(
                configValue.getValue().toBuilder().setItem(
                        configValue.getValue().getItem().toBuilder().setFile(
                                configValue.getValue().getItem().getFile().toBuilder().setPath(path).build()
                        ).build()
                ).build()
        );
        return entryBuilder.build();
    }

    public static Resources.Entry updateEntryConfigValueList(Resources.Entry entry, List<Resources.ConfigValue> configValueList) {
        Resources.Entry.Builder entryBuilder = entry.toBuilder();
        entryBuilder.clearConfigValue();
        entryBuilder.addAllConfigValue(configValueList);
        return entryBuilder.build();
    }

    public static Resources.Entry updateEntryName(Resources.Entry entry, String name) {
        Resources.Entry.Builder builder = entry.toBuilder();
        builder.setName(name);
        return builder.build();
    }

    public static void checkConfiguration(Resources.Entry entry) {
        if (entry.getConfigValueCount() == 0) return;
        Set<Resources.ConfigValue> configValues = new HashSet<>();
        for (Resources.ConfigValue configValue : entry.getConfigValueList()) {
            if (configValues.contains(configValue)) {
                throw new IllegalArgumentException("duplicate configuration for entry: " + entry.getName());
            }
            configValues.add(configValue);
        }
    }

    // Composite key (packageId, typeId, entryId) packed into a long. Used by rewriteEntries
    // to identify entries across passes without losing the surrounding proto state.
    public static long composeEntryKey(int packageId, int typeId, int entryId) {
        return ((long) packageId << 32)
                | (((long) typeId & 0xFFFFL) << 16)
                | ((long) entryId & 0xFFFFL);
    }

    /**
     * Rewrites entries in a {@link Resources.ResourceTable} in place, preserving the
     * original {@code ResourceTable}, {@code Package}, and {@code Type} messages — including
     * any unknown protobuf fields that the bundled aapt2-proto schema does not understand.
     *
     * <p>This matters for AABs produced against newer AAPT2 (e.g. {@code com.google.android.material}
     * &gt;= 1.7.0 introduces the {@code macro} resource type and {@code overlayable} blocks at the
     * package level). Building the table from scratch via {@link ResourcesTableBuilder} drops
     * those fields and yields an AAB that Google Play rejects.
     *
     * @param original     the original resource table; never mutated
     * @param replacements map keyed by {@link #composeEntryKey} returning the new {@link Resources.Entry}
     *                     to install at that slot. Entries not present in the map are left untouched.
     */
    public static Resources.ResourceTable rewriteEntries(
            Resources.ResourceTable original,
            Map<Long, Resources.Entry> replacements) {
        Resources.ResourceTable.Builder tableBuilder = original.toBuilder();
        for (int p = 0; p < tableBuilder.getPackageCount(); p++) {
            Resources.Package.Builder pkgBuilder = tableBuilder.getPackageBuilder(p);
            int packageId = pkgBuilder.getPackageId().getId();
            for (int t = 0; t < pkgBuilder.getTypeCount(); t++) {
                Resources.Type.Builder typeBuilder = pkgBuilder.getTypeBuilder(t);
                int typeId = typeBuilder.getTypeId().getId();
                for (int e = 0; e < typeBuilder.getEntryCount(); e++) {
                    Resources.Entry oldEntry = typeBuilder.getEntry(e);
                    int entryId = oldEntry.getEntryId().getId();
                    Resources.Entry newEntry = replacements.get(composeEntryKey(packageId, typeId, entryId));
                    if (newEntry != null) {
                        typeBuilder.setEntry(e, newEntry);
                    }
                }
            }
        }
        return tableBuilder.build();
    }
}
