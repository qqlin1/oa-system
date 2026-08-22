package com.qqlin.oa.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UserCreateDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(min=3,max=20,message = "用户名长度必须在3到20个字符之间")
    private String username;
    @NotBlank(message = "密码不能为空")
    @Size(min=6,max=32,message="密码长度必须在 6 到 32 个字符之间")
    private String password;

    private String phone;
    private String name;
    private Long departmentId;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }
}
