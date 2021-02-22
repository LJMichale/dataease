package io.dataease.service.sys;

import io.dataease.base.domain.SysDept;
import io.dataease.base.domain.SysDeptExample;
import io.dataease.base.mapper.SysDeptMapper;
import io.dataease.base.mapper.ext.ExtDeptMapper;
import io.dataease.commons.utils.BeanUtils;
import io.dataease.controller.sys.request.DeptCreateRequest;
import io.dataease.controller.sys.request.DeptDeleteRequest;
import io.dataease.controller.sys.request.DeptStatusRequest;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DeptService {

    private final static Integer DEPT_ROOT_LEVEL = 0;
    private final static Integer DEFAULT_SUBCOUNT = 0;
    public final static Long DEPT_ROOT_PID = 0L;

    @Resource
    private SysDeptMapper sysDeptMapper;


    @Resource
    private ExtDeptMapper extDeptMapper;

    public List<SysDept> nodesByPid(Long pid){
        SysDeptExample example = new SysDeptExample();
        SysDeptExample.Criteria criteria = example.createCriteria();
        if (ObjectUtils.isEmpty(pid)){
            criteria.andPidEqualTo(0L);
        }else {
            criteria.andPidEqualTo(pid);
        }
        example.setOrderByClause("dept_sort");
        List<SysDept> sysDepts = sysDeptMapper.selectByExample(example);
        return sysDepts;
    }

    @Transactional
    public boolean add(DeptCreateRequest deptCreateRequest){
        SysDept sysDept = BeanUtils.copyBean(new SysDept(), deptCreateRequest);

        if (deptCreateRequest.isTop()){
            sysDept.setPid(DEPT_ROOT_PID);
        }
        Date now = new Date();
        sysDept.setCreateTime(now);
        sysDept.setUpdateTime(now);
        sysDept.setCreateBy(null);
        sysDept.setUpdateBy(null);
        sysDept.setSubCount(DEFAULT_SUBCOUNT);
        try {
            int insert = sysDeptMapper.insert(sysDept);
            Long pid = null;
            if ((pid = sysDept.getPid()) != DEPT_ROOT_PID ){
                //这里需要更新上级节点SubCount
                extDeptMapper.incrementalSubcount(pid);
            }
            if (insert == 1){
                return true;
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return false;
    }

    @Transactional
    public int batchDelete(List<DeptDeleteRequest> requests){
       /* Integer index = ids.stream().map(sysDeptMapper::deleteByPrimaryKey).reduce(Integer::sum).orElse(-1);
        return index;*/
        List<Long> ids = requests.stream().map(request -> {
            Long pid = request.getPid();
            if (pid != DEPT_ROOT_PID){
                extDeptMapper.decreasingSubcount(pid);
            }
            return request.getDeptId();
        }).collect(Collectors.toList());
        return extDeptMapper.batchDelete(ids);
    }

    @Transactional
    public int update(DeptCreateRequest deptCreateRequest){
        SysDept sysDept = BeanUtils.copyBean(new SysDept(), deptCreateRequest);
        if (deptCreateRequest.isTop()){
            sysDept.setPid(DEPT_ROOT_PID);
        }
        sysDept.setUpdateTime(new Date());
        sysDept.setUpdateBy(null);
        Long deptId = sysDept.getDeptId();
        SysDept dept_old = sysDeptMapper.selectByPrimaryKey(deptId);
        //如果PID发生了改变
        //判断oldPid是否是跟节点PID ？ nothing : parent.subcount-1
        //判断newPid是否是跟节点PID ？ nothing : parent.subcount+1
        if (sysDept.getPid() != dept_old.getPid()){
            Long oldPid = dept_old.getPid();
            if (oldPid != DEPT_ROOT_PID){
                extDeptMapper.decreasingSubcount(oldPid);
            }
            if (sysDept.getPid() != DEPT_ROOT_PID){
                extDeptMapper.incrementalSubcount(sysDept.getPid());
            }
        }
        return sysDeptMapper.updateByPrimaryKeySelective(sysDept);
    }

    public int updateStatus(DeptStatusRequest request){
        Long deptId = request.getDeptId();
        boolean status = request.isStatus();
        SysDept sysDept = new SysDept();
        sysDept.setDeptId(deptId);
        sysDept.setEnabled(status);
        return sysDeptMapper.updateByPrimaryKeySelective(sysDept);
    }

}