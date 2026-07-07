package com.lusuoria.settlement.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.util.Date;

/**
 * Excel 导入批次记录。
 *
 * 导入改成异步以后（先只用于红人合作跟踪模块，数据量大容易超时），上传文件后立即
 * 创建一条这样的记录、马上把 id 返回给前端，实际的导入过程在后台慢慢跑，跑完再回填
 * 这条记录的统计结果和详细信息。前端可以随时通过"导入历史"页面查看某次导入的进度和结果，
 * 不用再傻等在原地。
 */
@Entity
@Table(name = "import_batches")
@Getter
@Setter
public class ImportBatch extends BaseEntity {

    /** 导入的是哪个模块，目前只有 COLLABORATION_TRACKING 一个取值，预留扩展空间 */
    @Column(name = "module", nullable = false)
    private String module;

    /** 上传的文件名，方便回头核对是哪个文件 */
    @Column(name = "file_name")
    private String fileName;

    /** 谁发起的这次导入 */
    @Column(name = "uploaded_by_name")
    private String uploadedByName;

    /** PROCESSING（处理中）/ COMPLETED（已完成）/ FAILED（整体失败，比如文件本身损坏打不开） */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "started_at")
    private Date startedAt;

    @Column(name = "completed_at")
    private Date completedAt;

    private Integer totalRows;
    private Integer successCount;
    private Integer updateCount;
    private Integer failCount;

    /** 详细结果，跟以前"导入结果"弹窗里看到的内容一样，每条一行 */
    @Column(name = "result_detail", columnDefinition = "TEXT")
    private String resultDetail;

    /** 整个批次处理失败时（文件损坏、系统异常等）记录的顶层错误信息 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
