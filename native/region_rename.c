#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/fscrypt.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "--policy") == 0) {
        int fd = open(argv[2], O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (fd < 0) { perror("open"); return 1; }
        struct fscrypt_policy_v1 policy = {0};
        if (ioctl(fd, FS_IOC_GET_ENCRYPTION_POLICY, &policy) != 0) {
            printf("POLICY_ERROR=%d %s\n", errno, strerror(errno));
            close(fd);
            return 1;
        }
        close(fd);
        const unsigned char *bytes = (const unsigned char *)&policy;
        printf("POLICY=");
        for (size_t i = 0; i < sizeof(policy); ++i) printf("%02x", bytes[i]);
        printf("\n");
        return 0;
    }
    if (argc != 3) {
        fprintf(stderr, "usage: region-rename SOURCE DEST | --policy DIRECTORY\n");
        return 2;
    }
    /* No mv-style copy/delete fallback, including when fscrypt returns EXDEV. */
    if (syscall(__NR_renameat2, AT_FDCWD, argv[1], AT_FDCWD, argv[2], 1) != 0) {
        fprintf(stderr, "RENAME_ERROR=%d %s: %s -> %s\n", errno, strerror(errno), argv[1], argv[2]);
        return 1;
    }
    puts("RENAMED");
    return 0;
}
