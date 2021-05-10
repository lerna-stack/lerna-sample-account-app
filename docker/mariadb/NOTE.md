 MariaDB doesn't handle subdirectories in `initdb` as a default.
 If you want to put your init SQLs in subdirectories, some scripts will be needed.
 
 See
  - https://gist.github.com/tksugimoto/2ba7f56fad7c3fadb91c9b5ebf9d0518
  - https://github.com/docker-library/mariadb/blob/master/10.3/docker-entrypoint.sh#L53-L79
 
