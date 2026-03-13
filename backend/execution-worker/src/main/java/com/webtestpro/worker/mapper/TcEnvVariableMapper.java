package com.webtestpro.worker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webtestpro.worker.entity.TcEnvVariable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TcEnvVariableMapper extends BaseMapper<TcEnvVariable> {

    @Select("SELECT * FROM tc_env_variable WHERE env_id = #{envId} AND is_deleted = 0")
    List<TcEnvVariable> selectByEnvId(Long envId);
}
