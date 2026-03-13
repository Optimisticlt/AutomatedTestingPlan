package com.webtestpro.worker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webtestpro.worker.entity.TcStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TcStepMapper extends BaseMapper<TcStep> {

    /** 按 stepOrder 升序查询用例的全部步骤（含逻辑删除过滤） */
    @Select("SELECT * FROM tc_step WHERE case_id = #{caseId} AND is_deleted = 0 ORDER BY step_order ASC")
    List<TcStep> selectByCaseIdOrdered(Long caseId);
}
