package com.webtestpro.worker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webtestpro.worker.entity.TcLocator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TcLocatorMapper extends BaseMapper<TcLocator> {

    /** 按稳定性评分降序查询步骤关联的全部定位器 */
    @Select("SELECT * FROM tc_locator WHERE step_id = #{stepId} AND is_deleted = 0 ORDER BY stability_score DESC")
    List<TcLocator> selectByStepIdOrdered(Long stepId);
}
