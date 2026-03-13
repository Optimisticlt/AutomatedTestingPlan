package com.webtestpro.worker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webtestpro.worker.entity.TcPlanCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TcPlanCaseMapper extends BaseMapper<TcPlanCase> {

    @Select("SELECT * FROM tc_plan_case_relation WHERE plan_id = #{planId} AND is_deleted = 0 ORDER BY sort_order ASC")
    List<TcPlanCase> selectByPlanIdOrdered(Long planId);
}
