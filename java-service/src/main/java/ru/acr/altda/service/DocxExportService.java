package ru.acr.altda.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ru.acr.altda.store.ProgramStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Builds a .docx from {@code templates/program-template.docx} (styled base)
 * covering title page, explanatory note, outcomes, curriculum hours table,
 * topic content, assessment and sources. Order / teacher / signature fields stay blank.
 */
@Service
public class DocxExportService {

    public byte[] export(ProgramStore.ProgramSnapshot snapshot) {
        JsonNode program = snapshot.program();
        try (InputStream in = new ClassPathResource("templates/program-template.docx").getInputStream();
             XWPFDocument doc = new XWPFDocument(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Clear template body paragraphs (keep sectPr via POI by removing runs' paras)
            clearBody(doc);

            String programType = program.path("programType").asText("DOOP_GENERAL");
            JsonNode header = program.path("header");
            String title = header.path("title").asText("Программа");

            addTitle(doc, title);
            addParagraph(doc, "Тип программы: " + ("DPP_PK".equals(programType)
                    ? "ДПП повышения квалификации"
                    : "ДООП (общеразвивающая)"));
            addParagraph(doc, "Направленность / IT-направление: " + header.path("itDirection").asText("—"));
            addParagraph(doc, "Объём: " + header.path("totalHours").asText("—") + " академических часов");
            addParagraph(doc, "Форма обучения: " + header.path("studyForm").asText("—"));
            addParagraph(doc, "Документ: " + header.path("issuedDocument").asText("—"));

            heading(doc, "1. Титульные реквизиты (заполняет человек)");
            addParagraph(doc, "Номер приказа: _______________");
            addParagraph(doc, "Дата приказа: _______________");
            addParagraph(doc, "Утвердил: _______________");
            addParagraph(doc, "Методист (ФИО): _______________");
            addParagraph(doc, "Эксперт-практик (ФИО): _______________");
            addParagraph(doc, "Подписи: _______________ / _______________");

            heading(doc, "2. Пояснительная записка");
            JsonNode audience = header.path("audience");
            addParagraph(doc, "Аудитория: " + audience.path("description").asText("—"));
            addParagraph(doc, "Входной уровень: " + audience.path("entryLevel").asText("—"));
            if ("DPP_PK".equals(programType)) {
                addParagraph(doc, "Требования к поступлению: "
                        + audience.path("entryRequirements").asText("—"));
                JsonNode standards = program.path("dpp").path("profStandards");
                if (standards.isArray()) {
                    addParagraph(doc, "Профстандарты:");
                    for (JsonNode ps : standards) {
                        addParagraph(doc, "• " + ps.path("standardCode").asText()
                                + " " + ps.path("standardTitle").asText()
                                + (ps.path("verified").asBoolean(false) ? "" : " [не подтверждён]"));
                    }
                }
            } else {
                addParagraph(doc, "Направленность ДООП: " + program.path("doop").path("focus").asText("—"));
            }
            addParagraph(doc, "Цель: " + program.path("goal").path("text").asText("—"));
            JsonNode tasks = program.path("goal").path("tasks");
            if (tasks.isArray()) {
                for (JsonNode t : tasks) {
                    addParagraph(doc, "— " + t.asText());
                }
            }

            heading(doc, "3. Планируемые результаты (компетенции / ЗУН)");
            for (JsonNode c : iterable(program.path("competencies"))) {
                addParagraph(doc, c.path("id").asText() + ". " + c.path("title").asText());
                for (JsonNode k : iterable(c.path("knowledge"))) {
                    addParagraph(doc, "  Знать [" + k.path("id").asText() + "]: " + k.path("text").asText());
                }
                for (JsonNode k : iterable(c.path("skills"))) {
                    addParagraph(doc, "  Уметь [" + k.path("id").asText() + "]: " + k.path("text").asText());
                }
                for (JsonNode k : iterable(c.path("abilities"))) {
                    addParagraph(doc, "  Владеть [" + k.path("id").asText() + "]: " + k.path("text").asText());
                }
            }

            heading(doc, "4. Учебный план (часы)");
            XWPFTable table = doc.createTable(1, 8);
            fillHeaderRow(table.getRow(0),
                    "Модуль / тема", "Лек.", "Пр.", "Лаб.", "Сам.", "Атт.", "Конт.", "Всего");
            JsonNode modules = program.path("curriculum").path("modules");
            for (JsonNode mod : iterable(modules)) {
                XWPFTableRow row = table.createRow();
                fillRow(row,
                        mod.path("id").asText() + " " + mod.path("title").asText(),
                        hours(mod, "lecture"), hours(mod, "practice"), hours(mod, "lab"),
                        hours(mod, "selfStudy"), hours(mod, "assessment"),
                        hours(mod, "contact"), hours(mod, "total"));
                for (JsonNode topic : iterable(mod.path("topics"))) {
                    XWPFTableRow tr = table.createRow();
                    fillRow(tr,
                            "  " + topic.path("id").asText() + " " + topic.path("title").asText(),
                            hours(topic, "lecture"), hours(topic, "practice"), hours(topic, "lab"),
                            hours(topic, "selfStudy"), hours(topic, "assessment"),
                            hours(topic, "contact"), hours(topic, "total"));
                }
            }
            JsonNode fa = program.path("finalAttestation");
            XWPFTableRow faRow = table.createRow();
            fillRow(faRow,
                    "Итоговая аттестация (" + fa.path("form").asText("—") + ")",
                    hours(fa, "lecture"), hours(fa, "practice"), hours(fa, "lab"),
                    hours(fa, "selfStudy"), hours(fa, "assessment"),
                    hours(fa, "contact"), hours(fa, "total"));
            JsonNode totals = program.path("curriculum").path("totals");
            XWPFTableRow tot = table.createRow();
            fillRow(tot,
                    "ИТОГО",
                    num(totals, "lecture"), num(totals, "practice"), num(totals, "lab"),
                    num(totals, "selfStudy"), num(totals, "assessment"),
                    num(totals, "contact"), num(totals, "total"));

            heading(doc, "5. Содержание тем");
            for (JsonNode mod : iterable(modules)) {
                addParagraph(doc, mod.path("id").asText() + ". " + mod.path("title").asText());
                for (JsonNode topic : iterable(mod.path("topics"))) {
                    addParagraph(doc, topic.path("id").asText() + " " + topic.path("title").asText()
                            + " (" + hours(topic, "total") + " ч)");
                    for (JsonNode act : iterable(topic.path("activities"))) {
                        addParagraph(doc, "  [" + act.path("kind").asText() + " "
                                + act.path("hours").asText() + " ч] "
                                + act.path("content").asText(""));
                    }
                }
            }

            heading(doc, "6. Оценочные средства");
            addParagraph(doc, "Итоговая аттестация: " + fa.path("form").asText("—")
                    + ". " + fa.path("description").asText(""));
            for (JsonNode test : iterable(program.path("assessment").path("tests"))) {
                addParagraph(doc, test.path("id").asText() + " — " + test.path("title").asText()
                        + " (" + test.path("questions").size() + " вопр.)");
            }
            for (JsonNode task : iterable(program.path("assessment").path("seminarTasks"))) {
                addParagraph(doc, "Задание: " + task.path("title").asText(task.path("id").asText()));
            }

            heading(doc, "7. Источники");
            for (JsonNode src : iterable(program.path("sources"))) {
                String mark = src.path("verified").asBoolean(false) ? "" : " [источник не подтверждён]";
                addParagraph(doc, src.path("id").asText() + ". " + src.path("citation").asText() + mark);
            }

            doc.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("DOCX export failed: " + ex.getMessage(), ex);
        }
    }

    private static void clearBody(XWPFDocument doc) {
        int n = doc.getBodyElements().size();
        for (int i = n - 1; i >= 0; i--) {
            doc.removeBodyElement(i);
        }
    }

    private static void addTitle(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Title");
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(18);
        run.setColor("0F6B5C");
    }

    private static void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading1");
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(14);
    }

    private static void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Normal");
        XWPFRun run = p.createRun();
        run.setText(text == null ? "" : text);
        run.setFontSize(11);
    }

    private static void fillHeaderRow(XWPFTableRow row, String... cells) {
        ensureCells(row, cells.length);
        for (int i = 0; i < cells.length; i++) {
            setCell(row.getCell(i), cells[i], true);
        }
    }

    private static void fillRow(XWPFTableRow row, String... cells) {
        ensureCells(row, cells.length);
        for (int i = 0; i < cells.length; i++) {
            setCell(row.getCell(i), cells[i], false);
        }
    }

    private static void ensureCells(XWPFTableRow row, int count) {
        while (row.getTableCells().size() < count) {
            row.addNewTableCell();
        }
    }

    private static void setCell(XWPFTableCell cell, String text, boolean bold) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        XWPFRun run = p.createRun();
        run.setText(text == null ? "" : text);
        run.setBold(bold);
        run.setFontSize(10);
    }

    private static String hours(JsonNode node, String key) {
        return num(node.path("hours"), key);
    }

    private static String num(JsonNode node, String key) {
        if (node == null || node.isMissingNode() || !node.has(key)) {
            return "0";
        }
        double v = node.path(key).asDouble(0);
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.valueOf(v);
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> list = new ArrayList<>();
        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) {
            list.add(it.next());
        }
        return list;
    }
}
