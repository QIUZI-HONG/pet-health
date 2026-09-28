package com.pethealth.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.file.domain.FileObject;
import org.apache.ibatis.annotations.Mapper;

/** {@code file_object} 的读写。软删除由 MyBatis-Plus 自动加条件（ADR-0011）。 */
@Mapper
public interface FileObjectMapper extends BaseMapper<FileObject> {
}
