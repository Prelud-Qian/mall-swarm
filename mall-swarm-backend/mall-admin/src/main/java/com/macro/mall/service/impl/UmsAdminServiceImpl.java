package com.macro.mall.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONUtil;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.api.ResultCode;
import com.macro.mall.common.constant.AuthConstant;
import com.macro.mall.common.dto.UserDto;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.dao.UmsAdminRoleRelationDao;
import com.macro.mall.dto.UmsAdminParam;
import com.macro.mall.dto.UpdateAdminPasswordParam;
import com.macro.mall.mapper.UmsAdminLoginLogMapper;
import com.macro.mall.mapper.UmsAdminMapper;
import com.macro.mall.mapper.UmsAdminRoleRelationMapper;
import com.macro.mall.model.*;
import com.macro.mall.service.UmsAdminCacheService;
import com.macro.mall.service.UmsAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;
import java.util.stream.Collectors;

/**
 * UmsAdminService实现类
 * Created by macro on 2018/4/26.
 */
@Service
public class UmsAdminServiceImpl implements UmsAdminService {
    private static final Logger LOGGER = LoggerFactory.getLogger(UmsAdminServiceImpl.class);

    // 登录失败锁定：同一用户名连续失败5次，锁定10分钟（防暴力破解）
    private static final String LOGIN_FAIL_KEY_PREFIX = "ums:admin:loginFail:";
    private static final int LOGIN_MAX_FAIL = 5;
    private static final long LOGIN_LOCK_SECONDS = 10 * 60;

    // refreshToken：Redis存储key前缀与有效期（7天，单位秒）
    private static final String REDIS_KEY_REFRESH_TOKEN = "ums:admin:refreshToken:";
    private static final long REFRESH_TOKEN_TIMEOUT = 7 * 24 * 60 * 60;


    @Autowired
    private UmsAdminMapper adminMapper;
    @Autowired
    private UmsAdminRoleRelationMapper adminRoleRelationMapper;
    @Autowired
    private UmsAdminRoleRelationDao adminRoleRelationDao;
    @Autowired
    private UmsAdminLoginLogMapper loginLogMapper;
    @Autowired
    private UmsAdminCacheService adminCacheService;
    @Autowired
    private RedisService redisService;

    @Override
    public UmsAdmin getAdminByUsername(String username) {
        UmsAdminExample example = new UmsAdminExample();
        example.createCriteria().andUsernameEqualTo(username);
        List<UmsAdmin> adminList = adminMapper.selectByExample(example);
        if (adminList != null && adminList.size() > 0) {
            return adminList.get(0);
        }
        return null;
    }

    @Override
    public UmsAdmin register(UmsAdminParam umsAdminParam) {
        UmsAdmin umsAdmin = new UmsAdmin();
        BeanUtils.copyProperties(umsAdminParam, umsAdmin);
        umsAdmin.setCreateTime(new Date());
        umsAdmin.setStatus(1);
        //查询是否有相同用户名的用户
        UmsAdminExample example = new UmsAdminExample();
        example.createCriteria().andUsernameEqualTo(umsAdmin.getUsername());
        List<UmsAdmin> umsAdminList = adminMapper.selectByExample(example);
        if (umsAdminList.size() > 0) {
            return null;
        }
        //将密码进行加密操作
        String encodePassword = BCrypt.hashpw(umsAdmin.getPassword());
        umsAdmin.setPassword(encodePassword);
        adminMapper.insert(umsAdmin);
        return umsAdmin;
    }

    /**
     * 第 0 天 9:00：小明登录
     * 他输入 admin / admin123456，login 方法依次跑：
     *
     * 第一关防守：查 Redis 里 ums:admin:loginFail:admin——没有（第一次来），放行。
     * 第二关验人：数据库里查到 admin、BCrypt 比对密码一致、状态正常 → 通过。
     * 第三关发证，在 Redis 里留下 3 个新 key：
     *
     *
     * ① Authorization:login:token:eyJ...      = 3(adminId)   [2小时后过期]  ← accessToken
     * ② Authorization:login:session:3          = {用户信息+权限}              ← 会话数据
     * ③ ums:admin:refreshToken:550e8400...     = 3(adminId)   [7天后过期]    ← refreshToken
     * 前端拿到 {token, tokenHead, refreshToken} 三样存起来。
     *
     * 9:00 ~ 11:00：正常使用（accessToken 在有效期内）
     * 小明每次点页面，请求头带 Authorization: Bearer eyJ... → 网关拿 token 拼 key ① 查 Redis → 查得到 = 有效 → 放行。login 方法完全不参与，只是 key ① 在服务。
     *
     * 11:00：accessToken 到期（2 小时到了）
     * Redis 自动删掉 key ①。小明下一个请求 → 网关查 key ① → 查不到 → 返回 401。
     *
     * 前端拦截器自动接管（用户无感）：拿 key ③ 的 refreshToken 调换发接口 → 后端查 key ③ → 还在 → 知道这是 adminId=3 → 重新执行 ⑥（StpUtil.login(3)）签发新 accessToken（新的 key ①），同时旧 refreshToken 作废、发新 refreshToken（防重放）→ 前端拿到新凭证重试刚才的请求。
     *
     * 小明全程无感，只是 Redis 里的 key 换了新的。
     *
     * 第 0 天 ~ 第 7 天：这个"到期→换发"循环一直转
     * 每次换发，refreshToken 也换新（7 天重新计时）——所以只要小明 7 天内登录过一次系统，就一直免登录。
     *
     * 第 7 天：refreshToken 也过期了
     * 小明 7 天没来。key ③ 被 Redis 自动删除。他再访问 → accessToken 早已过期（401）→ 前端拿 refreshToken 换发 → 后端查 key ③ → 查不到 → 返回"刷新令牌无效"→ 前端跳登录页 → 小明重新输账号密码 → 回到第 0 天的流程。
     * @param username 用户名
     * @param password 密码
     * @return
     */
    @Override
    public Map<String, Object> login(String username, String password) {

        // 登录失败锁定检查：Redis计数达到上限直接拒绝
        String failKey = LOGIN_FAIL_KEY_PREFIX + username;
        Object failCount = redisService.get(failKey);
        if (failCount != null && Integer.parseInt(String.valueOf(failCount)) >= LOGIN_MAX_FAIL) {
            Asserts.fail("登录失败次数过多，请10分钟后再试");
        }

        if(StrUtil.isEmpty(username)||StrUtil.isEmpty(password)){
            recordLoginFail(username);
            Asserts.fail("用户名或密码不能为空！");
        }
        UmsAdmin admin = getAdminByUsername(username);
        if(admin==null){
            recordLoginFail(username);
            Asserts.fail("找不到该用户！");
        }
        if (!BCrypt.checkpw(password, admin.getPassword())) {
            recordLoginFail(username);
            Asserts.fail("密码不正确！");
        }
        if(admin.getStatus()!=1){
            recordLoginFail(username);
            Asserts.fail("该账号已被禁用！");
        }
        // 登录校验成功后，一行代码实现登录
        // StpUtil.login() 这一行内部干完了"生成 token + 存 Redis + 绑定 loginId"全部工作——这就是 Sa-Token 的"一行登录"。
        StpUtil.login(admin.getId());
        redisService.del(failKey);
        UserDto userDto = new UserDto();
        userDto.setId(admin.getId());
        userDto.setUsername(admin.getUsername());
        userDto.setClientId(AuthConstant.ADMIN_CLIENT_ID);
        List<UmsResource> resourceList = getResourceList(admin.getId());
        List<String> permissionList = resourceList.stream().map(item -> item.getId() + ":" + item.getName()).toList();
        userDto.setPermissionList(permissionList);
        // 将用户信息存储到Session中
        /**
         * session 在 StpUtil.login() 那一行就已经创建好了（和 token 同时生成，都存在 Redis 里）
         * getSession().set(key, userDto) 只是往这个 session 里塞东西：用户信息 + 权限列表
         *
         * 它的作用是给后续请求用的：用户带着 token 再来时，服务端凭 token 找到 loginId、再找到 session，
         * 把里面的 userDto 取出来——比如网关的 StpInterfaceImpl 查权限时就是读这里的权限列表。
         */
        StpUtil.getSession().set(AuthConstant.STP_ADMIN_INFO,userDto);
        // 获取当前登录用户Token信息
        /**
         * 把"钥匙"交给前端。 token 生成后躺在 Redis 里，但前端必须知道这个字符串才能携带它。
         * getTokenInfo() 把 token 对象取出来（tokenName + tokenValue），
         * Controller 包进响应返回——前端拿到 eyJ0eXAi... 这段字符串，
         * 以后拼成 Authorization: Bearer xxx 带在请求头上。
         */
        SaTokenInfo saTokenInfo = StpUtil.getTokenInfo();
        // 生成refreshToken：UUID随机串，绑定loginId存Redis，7天过期
        // 为什么自己管：Sa-Token没有内置refresh机制，自管最简单可控
        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        redisService.set(REDIS_KEY_REFRESH_TOKEN + refreshToken, admin.getId(), REFRESH_TOKEN_TIMEOUT);
        insertLoginLog(admin);

        // 组装返回：前端需要三个东西——请求凭证、凭证前缀、换新凭证的钥匙
        Map<String, Object> result = new HashMap<>();
        result.put("token", saTokenInfo.getTokenValue());
        result.put("tokenHead", "Bearer ");
        result.put("refreshToken", refreshToken);
        return result;
    }

    /**
     * refreshToken 换发接口
     */
    @Override
    public Map<String, Object> refreshToken(String refreshToken) {
        // 拿前端传来的"后端login方法中生成的 refreshToken"拼key查Redis——查得到=有效（7天内），查不到=过期
        Object loginId = redisService.get(REDIS_KEY_REFRESH_TOKEN + refreshToken);
        if (loginId == null) {
            Asserts.fail("刷新令牌无效或已过期，请重新登录");
        }
        Long adminId = Long.valueOf(String.valueOf(loginId));
        // 重新签发accessToken（和登录时同一个动作：StpUtil.login）
        StpUtil.login(adminId);
        SaTokenInfo saTokenInfo = StpUtil.getTokenInfo();
        // 旧refreshToken一次性作废，并换发新的
        redisService.del(REDIS_KEY_REFRESH_TOKEN + refreshToken);
        String newRefreshToken = UUID.randomUUID().toString().replace("-", "");
        redisService.set(REDIS_KEY_REFRESH_TOKEN + newRefreshToken, adminId, REFRESH_TOKEN_TIMEOUT);
        // 返回新凭证给前端
        Map<String, Object> result = new HashMap<>();
        result.put("token", saTokenInfo.getTokenValue());
        result.put("tokenHead", "Bearer ");
        result.put("refreshToken", newRefreshToken);
        return result;
    }

    /**
     * 记录一次登录失败：计数+1并重置锁定时长（每次失败重新计时10分钟）
     */
    private void recordLoginFail(String username) {
        String failKey = LOGIN_FAIL_KEY_PREFIX + username;
        redisService.incr(failKey, 1);
        redisService.expire(failKey, LOGIN_LOCK_SECONDS);
    }

    /**
     * 添加登录记录
     */
    private void insertLoginLog(UmsAdmin admin) {
        if(admin==null) return;
        UmsAdminLoginLog loginLog = new UmsAdminLoginLog();
        loginLog.setAdminId(admin.getId());
        loginLog.setCreateTime(new Date());
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        loginLog.setIp(request.getRemoteAddr());
        loginLogMapper.insert(loginLog);
    }

    /**
     * 根据用户名修改登录时间
     */
    private void updateLoginTimeByUsername(String username) {
        UmsAdmin record = new UmsAdmin();
        record.setLoginTime(new Date());
        UmsAdminExample example = new UmsAdminExample();
        example.createCriteria().andUsernameEqualTo(username);
        adminMapper.updateByExampleSelective(record, example);
    }

    @Override
    public UmsAdmin getItem(Long id) {
        return adminMapper.selectByPrimaryKey(id);
    }

    @Override
    public List<UmsAdmin> list(String keyword, Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        UmsAdminExample example = new UmsAdminExample();
        UmsAdminExample.Criteria criteria = example.createCriteria();
        if (!StringUtils.isEmpty(keyword)) {
            criteria.andUsernameLike("%" + keyword + "%");
            example.or(example.createCriteria().andNickNameLike("%" + keyword + "%"));
        }
        return adminMapper.selectByExample(example);
    }

    @Override
    public int update(Long id, UmsAdmin admin) {
        admin.setId(id);
        UmsAdmin rawAdmin = adminMapper.selectByPrimaryKey(id);
        if(rawAdmin.getPassword().equals(admin.getPassword())){
            //与原加密密码相同的不需要修改
            admin.setPassword(null);
        }else{
            //与原加密密码不同的需要加密修改
            if(StrUtil.isEmpty(admin.getPassword())){
                admin.setPassword(null);
            }else{
                admin.setPassword(BCrypt.hashpw(admin.getPassword()));
            }
        }
        int count = adminMapper.updateByPrimaryKeySelective(admin);
        adminCacheService.delAdmin(id);
        return count;
    }

    @Override
    public int delete(Long id) {
        int count = adminMapper.deleteByPrimaryKey(id);
        adminCacheService.delAdmin(id);
        return count;
    }

    @Override
    public int updateRole(Long adminId, List<Long> roleIds) {
        int count = roleIds == null ? 0 : roleIds.size();
        //先删除原来的关系
        UmsAdminRoleRelationExample adminRoleRelationExample = new UmsAdminRoleRelationExample();
        adminRoleRelationExample.createCriteria().andAdminIdEqualTo(adminId);
        adminRoleRelationMapper.deleteByExample(adminRoleRelationExample);
        //建立新关系
        if (!CollectionUtils.isEmpty(roleIds)) {
            List<UmsAdminRoleRelation> list = new ArrayList<>();
            for (Long roleId : roleIds) {
                UmsAdminRoleRelation roleRelation = new UmsAdminRoleRelation();
                roleRelation.setAdminId(adminId);
                roleRelation.setRoleId(roleId);
                list.add(roleRelation);
            }
            adminRoleRelationDao.insertList(list);
        }
        return count;
    }

    @Override
    public List<UmsRole> getRoleList(Long adminId) {
        return adminRoleRelationDao.getRoleList(adminId);
    }

    @Override
    public List<UmsResource> getResourceList(Long adminId) {
        return adminRoleRelationDao.getResourceList(adminId);
    }

    @Override
    public int updatePassword(UpdateAdminPasswordParam param) {
        if(StrUtil.isEmpty(param.getUsername())
                ||StrUtil.isEmpty(param.getOldPassword())
                ||StrUtil.isEmpty(param.getNewPassword())){
            return -1;
        }
        UmsAdminExample example = new UmsAdminExample();
        example.createCriteria().andUsernameEqualTo(param.getUsername());
        List<UmsAdmin> adminList = adminMapper.selectByExample(example);
        if(CollUtil.isEmpty(adminList)){
            return -2;
        }
        UmsAdmin umsAdmin = adminList.get(0);
        if(!BCrypt.checkpw(param.getOldPassword(),umsAdmin.getPassword())){
            return -3;
        }
        umsAdmin.setPassword(BCrypt.hashpw(param.getNewPassword()));
        adminMapper.updateByPrimaryKey(umsAdmin);
        adminCacheService.delAdmin(umsAdmin.getId());
        return 1;
    }

    @Override
    public UmsAdmin getCurrentAdmin() {
        UserDto userDto = (UserDto) StpUtil.getSession().get(AuthConstant.STP_ADMIN_INFO);
        UmsAdmin admin = adminCacheService.getAdmin(userDto.getId());
        if (admin == null) {
            admin = adminMapper.selectByPrimaryKey(userDto.getId());
            adminCacheService.setAdmin(admin);
        }
        return admin;
    }
    @Override
    public void logout() {
        //先清空缓存
        UserDto userDto = (UserDto) StpUtil.getSession().get(AuthConstant.STP_ADMIN_INFO);
        adminCacheService.delAdmin(userDto.getId());
        //再调用sa-token的登出方法
        StpUtil.logout();
    }
}
