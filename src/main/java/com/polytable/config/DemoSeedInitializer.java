package com.polytable.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polytable.repository.OpsSheetFieldRepository;
import com.polytable.repository.OpsSheetRowRepository;
import com.polytable.repository.OpsTableRegistryRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首次启动种子：当表格注册表完全为空时，创建一张「示例项目表」让新部署开箱即用。
 *
 * <p>用户删除示例表后不会复活（仅在注册表为空时写入）；生产环境可用
 * {@code DEMO_SEED_ENABLED=false} 关闭。</p>
 */
@Component
@ConditionalOnProperty(name = "demo.seed-enabled", havingValue = "true", matchIfMissing = true)
public class DemoSeedInitializer implements ApplicationRunner {

    public static final String DEMO_TABLE_ID = "tbl_demo_projects";

    private final OpsTableRegistryRepository registryRepository;
    private final OpsSheetFieldRepository fieldRepository;
    private final OpsSheetRowRepository rowRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DemoSeedInitializer(OpsTableRegistryRepository registryRepository,
                               OpsSheetFieldRepository fieldRepository,
                               OpsSheetRowRepository rowRepository) {
        this.registryRepository = registryRepository;
        this.fieldRepository = fieldRepository;
        this.rowRepository = rowRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!registryRepository.listActive().isEmpty()) {
                return;
            }
            registryRepository.insert(DEMO_TABLE_ID, "示例项目表", "blank", "all", null, 0, "system", "system", "hq");
            seedFields();
            seedRows();
            System.out.println("[PolyTable] 已创建示例表 " + DEMO_TABLE_ID + "（可在表格菜单中删除；DEMO_SEED_ENABLED=false 可关闭种子）");
        } catch (Exception e) {
            System.out.println("[PolyTable] 示例表种子跳过：" + e.getMessage());
        }
    }

    private void seedFields() throws Exception {
        int sort = 0;
        fieldRepository.insert(DEMO_TABLE_ID, "title", "项目名称", "text", "", sort++, "system");
        fieldRepository.insert(DEMO_TABLE_ID, "owner", "负责人", "text", "", sort++, "system");
        fieldRepository.insert(DEMO_TABLE_ID, "status", "状态", "select", "", optionsJson(
                option("未开始", "gray"), option("进行中", "blue"), option("已完成", "green"), option("已取消", "red")), sort++, "system");
        fieldRepository.insert(DEMO_TABLE_ID, "priority", "优先级", "select", "", optionsJson(
                option("P0", "red"), option("P1", "orange"), option("P2", "blue")), sort++, "system");
        fieldRepository.insert(DEMO_TABLE_ID, "tags", "标签", "multi", "", optionsJson(
                option("前端", "blue"), option("后端", "green"), option("文档", "gray"), option("性能", "orange")), sort++, "system");
        fieldRepository.insert(DEMO_TABLE_ID, "dueDate", "截止日期", "date", "", sort++, "system");
        fieldRepository.insert(DEMO_TABLE_ID, "delayed", "是否延期", "checkbox", "", sort++, "system");
        // 公式列：按字段标题引用（与运行时元数据解析一致），演示级联求值。
        fieldRepository.insert(DEMO_TABLE_ID, "summary", "摘要", "formula",
                "CONCATENATE(负责人, \" · \", 状态)", sort++, "system");
    }

    private void seedRows() throws Exception {
        List<Map<String, Object>> rows = List.of(
                row("官网首页改版", "张三", "进行中", "P0", List.of("前端", "文档"), "2026-12-10", "否"),
                row("公式引擎性能回归", "李四", "进行中", "P1", List.of("性能", "后端"), "2026-12-15", "否"),
                row("账户与权限体系", "张三", "未开始", "P1", List.of("后端"), "2026-12-20", "否"),
                row("视图筛选与分组", "李四", "未开始", "P2", List.of("前端"), "2026-12-25", "否"),
                row("实时协作稳定性", "王五", "进行中", "P0", List.of("后端", "性能"), "2026-12-08", "是"),
                row("部署文档与镜像", "赵六", "已完成", "P2", List.of("文档"), "2026-11-30", "否"),
                row("单元格复制粘贴", "王五", "已完成", "P1", List.of("前端"), "2026-11-25", "否"),
                row("大表滚动优化", "李四", "已完成", "P0", List.of("性能", "前端"), "2026-11-20", "否"),
                row("操作日志与撤销", "张三", "已取消", "P2", List.of("后端"), "2026-12-05", "否"),
                row("示例数据导入导出", "赵六", "未开始", "P2", List.of("后端", "文档"), "2027-01-10", "否"),
                row("多语言界面支持", "王五", "未开始", "P1", List.of("前端", "文档"), "2027-01-20", "否"),
                row("移动端适配", "李四", "进行中", "P2", List.of("前端"), "2027-01-31", "是")
        );
        int sort = 0;
        for (Map<String, Object> cells : rows) {
            rowRepository.insert(DEMO_TABLE_ID, "r_demo_" + String.format("%04d", ++sort),
                    objectMapper.writeValueAsString(cells), sort - 1, "system");
        }
    }

    private Map<String, Object> row(String title, String owner, String status, String priority,
                                    List<String> tags, String dueDate, String delayed) {
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("title", title);
        cells.put("owner", owner);
        cells.put("status", status);
        cells.put("priority", priority);
        cells.put("tags", tags);
        cells.put("dueDate", dueDate);
        cells.put("delayed", delayed);
        return cells;
    }

    private Map<String, Object> option(String value, String color) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("color", color);
        return item;
    }

    private String optionsJson(Map<String, Object>... options) throws Exception {
        return objectMapper.writeValueAsString(List.of(options));
    }
}
