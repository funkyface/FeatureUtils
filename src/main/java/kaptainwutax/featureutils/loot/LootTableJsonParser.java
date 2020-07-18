package kaptainwutax.featureutils.loot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Development utility class to generate loot tables from JSON files.
 *
 * This class is only needed when updating the loot tables, loot data should be
 * accessed from {@link MCLootTables} instead.
 */
public class LootTableJsonParser {

    private static final String OUTPUT_FILE_PATH = "loot_tables_output.txt";
    private static final String JSON_SOURCE_FILES_DIRECTORY = "src/main/resources/loot/v1_16/";

    public static void main(String[] args) {
        Tables tables = new Tables();
        for (Path jsonFilePath : getAllJsonFilesFromDirectory(JSON_SOURCE_FILES_DIRECTORY)) {
            JSONObject rootObject = new JSONObject(getJsonStringFromFile(jsonFilePath));
            String tableName = jsonFilePath
                    .getFileName()
                    .toString()
                    .replace(".json", "_CHEST")
                    .toUpperCase();
            tables.addLootTable(new LootTable(tableName, rootObject));
        }
        writeStringToFile(tables.toString(), OUTPUT_FILE_PATH);
    }

    private static String getJsonStringFromFile(Path filePath) {
        try {
            return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Path> getAllJsonFilesFromDirectory(String directoryPath) {
        try {
            return Files.find(
                    Paths.get(directoryPath),
                    3, // Max directory depth
                    ((path, basicFileAttributes) -> (basicFileAttributes.isRegularFile()))

            ).filter(path -> path.getFileName().toString().endsWith(".json"))
            .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeStringToFile(String string, String filePath) {
        try (PrintWriter printWriter = new PrintWriter(filePath)) {
            printWriter.println(string);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private static String getIndentation(int tabs) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < tabs; i++) {
            stringBuilder.append("\t");
        }
        return stringBuilder.toString();
    }

    private static List<JSONObject> getJsonObjectsFromJsonArray(JSONArray jsonArray) {
        List<JSONObject> jsonObjects = new ArrayList<>();
        for (int i = 0;; i++) {
            try {
                jsonObjects.add(jsonArray.getJSONObject(i));
            } catch (JSONException e) {
                // Out of objects
                break;
            }
        }
        return jsonObjects;
    }

    private static class Tables {
        List<LootTable> lootTables = new ArrayList<>();

        void addLootTable(LootTable lootTable) {
            lootTables.add(lootTable);
        }

        @Override
        public String toString() {
            return lootTables
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n\n"));
        }
    }

    private static class LootTable {
        String name;
        List<LootPool> lootPools = new ArrayList<>();

        public LootTable(String name, JSONObject rootObject) {
            this.name = name;
            JSONArray pools = rootObject.getJSONArray("pools");
            getJsonObjectsFromJsonArray(pools)
                    .forEach(pool -> lootPools.add(new LootPool(pool)));
        }

        @Override
        public String toString() {
            return String.format("%spublic static final LootTable %s = new LootTable(\n%s\n\t);",
                    getIndentation(1),
                    name.toUpperCase(),
                    lootPools
                            .stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(",\n"))
            );
        }
    }

    private static class LootPool {
        Roll roll;
        List<Entry> entries = new ArrayList<>();

        LootPool(JSONObject lootPoolObject) {
            this.roll = Roll.get(lootPoolObject);
            JSONArray entriesArray = lootPoolObject.getJSONArray("entries");
            getJsonObjectsFromJsonArray(entriesArray)
                    .forEach(entry -> entries.add(Entry.get(entry)));
        }

        @Override
        public String toString() {
            return String.format(
                    "%snew LootPool(%s,\n%s)",
                    getIndentation(3),
                    roll,
                    entries.stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(",\n"))
            );
        }

    }

    private interface Roll {
        static Roll get(JSONObject parentObject) {
            try {
                return new ConstantRoll(parentObject.getInt("rolls"));
            } catch (JSONException e) {
                return new UniformRoll(parentObject.getJSONObject("rolls"));
            }
        }
    }

    private static class ConstantRoll implements Roll {
        int value;

        ConstantRoll(int value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return String.format("new ConstantRoll(%d)", value);
        }
    }

    private static class UniformRoll implements Roll {
        float min;
        float max;

        UniformRoll(JSONObject rolls) {
            this.min = rolls.getFloat("min");
            this.max = rolls.getFloat("max");
        }

        @Override
        public String toString() {
            return String.format("new UniformRoll(%.1fF, %.1fF)", min, max);
        }
    }

    private abstract static class Entry {
        int weight;

        static Entry get(JSONObject entryObject) {
            if (entryObject.getString("type").equals("minecraft:empty")) {
                return new EmptyEntry(entryObject);
            } else {
                return new ItemEntry(entryObject);
            }
        }
    }

    private static class ItemEntry extends Entry {
        String itemName;
        List<EntryFunction> entryFunctions = new ArrayList<>();

        ItemEntry(JSONObject entryObject) {
            this.itemName = entryObject.getString("name");
            try {
                this.weight = entryObject.getInt("weight");
            } catch (JSONException e) {
                // No weight set, continue
            }
            try {
                JSONArray functionsObject = entryObject.getJSONArray("functions");
                getJsonObjectsFromJsonArray(functionsObject)
                        .forEach(function -> entryFunctions.add(EntryFunction.get(function)));
                if (this.itemName.equals("minecraft:book")
                        && entryFunctions
                            .stream()
                            .anyMatch(function -> function instanceof EnchantRandomlyFunction)) {
                    this.itemName = "minecraft:enchanted_book";
                }
            } catch (JSONException e) {
                // No functions exist, continue
            }
        }

        @Override
        public String toString() {
            return String.format(
                    "%snew ItemEntry(Item.%s%s)%s",
                    getIndentation(5),
                    itemName.replaceFirst("minecraft:", "").toUpperCase(),
                    (weight == 0) ? "" : (", " + weight),
                    entryFunctions
                        .stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(""))
            );
        }
    }

    private static class EmptyEntry extends Entry {
        EmptyEntry(JSONObject entryObject) {
            this.weight = entryObject.getInt("weight");
        }

        @Override
        public String toString() {
            return String.format(
                    "%snew EmptyEntry(%d)",
                    getIndentation(5),
                    weight
            );
        }
    }

    private interface EntryFunction {
        static EntryFunction get(JSONObject functionObject) {
            try {
                switch (functionObject.getString("function")) {
                    case "minecraft:set_count":
                        return SetCountFunction.get(functionObject);
                    case "minecraft:enchant_randomly":
                        return new EnchantRandomlyFunction();
                    case "minecraft:enchant_with_levels":
                        return new EnchantWithLevelsFunction();
                    case "minecraft:exploration_map":
                        return new ExplorationMapFunction();
                    case "minecraft:set_stew_effect":
                        return new SetStewEffectFunction();
                    case "minecraft:set_damage":
                        return new SetDamageFunction();
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
            throw new RuntimeException("Other functions not implemented yet");
        }
    }

    private interface SetCountFunction extends EntryFunction {
        static SetCountFunction get(JSONObject functionObject) {
            try {
                switch (functionObject.getJSONObject("count").getString("type")) {
                    case "minecraft:uniform":
                        return new UniformFunction(functionObject);
                }
            } catch (JSONException e) {
                return new ConstantSetCountFunction(functionObject.getInt("count"));
            }
            throw new RuntimeException("Other functions not implemented yet");
        }
    }

    // TODO: Fix entry functions when they are implemented

    private static class EnchantRandomlyFunction implements EntryFunction {
        @Override
        public String toString() {
            return " /* enchant_randomly */ ";
        }
    }

    private static class EnchantWithLevelsFunction implements EntryFunction {
        @Override
        public String toString() {
            return " /* enchant_with_levels */ ";
        }
    }

    private static class ExplorationMapFunction implements EntryFunction {
        @Override
        public String toString() {
            return " /* exploration_map */ ";
        }
    }

    private static class SetStewEffectFunction implements EntryFunction {
        @Override
        public String toString() {
            return " /* set_stew_effect */ ";
        }
    }

    private static class SetDamageFunction implements EntryFunction {
        @Override
        public String toString() {
            return " /* set_damage */ ";
        }
    }

    private static class UniformFunction implements SetCountFunction {
        float min;
        float max;

        UniformFunction(JSONObject functionObject) {
            this.min = functionObject.getJSONObject("count").getFloat("min");
            this.max = functionObject.getJSONObject("count").getFloat("max");
        }

        @Override
        public String toString() {
            return String.format(".apply(uniform(%.1fF, %.1fF))", min, max);
        }
    }

    private static class ConstantSetCountFunction implements SetCountFunction {
        int count;

        public ConstantSetCountFunction(int count) {
            this.count = count;
        }

        @Override
        public String toString() {
            return String.format(".apply(constant(%d))", count);
        }
    }

}
