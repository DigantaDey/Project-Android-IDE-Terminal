#ifndef FORGE_CHILD_POLICY_H
#define FORGE_CHILD_POLICY_H

#include <errno.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#if defined(__aarch64__)
#define FORGE_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define FORGE_ARCH AUDIT_ARCH_X86_64
#else
#error Unsupported native runtime ABI
#endif
#define ARCH_GUARD \
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)), \
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, FORGE_ARCH, 1, 0), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL), \
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)), \
    BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, 0x40000000, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)

static int restrict_child(int network) {
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) return -1;
    struct sock_filter network_filter[] = {
        ARCH_GUARD,
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 5, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_connect, 4, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_sendto, 3, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_sendmsg, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_sendmmsg, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES)
    };
    struct sock_fprog network_program = { .len = sizeof(network_filter) / sizeof(network_filter[0]), .filter = network_filter };
    if (!network && prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &network_program)) return -1;
    /* Prevent a child from inspecting/injecting its trusted companion parent. */
#define DENY_SYSCALL(number) \
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, number, 0, 1), \
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)
    struct sock_filter process_filter[] = {
        ARCH_GUARD,
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        DENY_SYSCALL(__NR_ptrace),
        DENY_SYSCALL(__NR_process_vm_readv),
        DENY_SYSCALL(__NR_process_vm_writev),
#ifdef __NR_io_uring_setup
        DENY_SYSCALL(__NR_io_uring_setup),
        DENY_SYSCALL(__NR_io_uring_enter),
        DENY_SYSCALL(__NR_io_uring_register),
#endif
        /* No Android Binder authority in native children (including DownloadManager brokers). */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_ioctl, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
        BPF_STMT(BPF_ALU | BPF_AND | BPF_K, 0xffff),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0x6201, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
#undef DENY_SYSCALL
    struct sock_fprog process_program = { .len = sizeof(process_filter) / sizeof(process_filter[0]), .filter = process_filter };
    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &process_program);
}


#endif
