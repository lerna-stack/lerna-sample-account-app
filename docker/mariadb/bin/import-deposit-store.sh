#!/usr/bin/env bash

readonly script_name=$(basename "$0")

function print_usage {
  cat <<EOF
Usage:
  ${script_name} <number of data>
EOF
}

function main {
  local nr_of_data="$1"

  if [[ -z "${nr_of_data}" ]] || [[ "${nr_of_data}" -lt 0 ]]
  then
    {
      echo "<number of data>: Specify a number greater than or equal to 0"
      echo
      print_usage
    } >&2
    exit 1
  fi

  generate_insert_statements "${nr_of_data}" | mariadb_cli
  echo "Done: inserted ${nr_of_data} rows."
}

function generate_insert_statements {
    local nr_of_data="$1"
    for i in $(seq ${nr_of_data})
    do
      echo "INSERT INTO deposit_store (account_no, amount, created_at) VALUES ('$((${RANDOM} % 10))', 10000, '$(date '+%F %T')');"
    done
}

function mariadb_cli {
  mariadb --user="${MYSQL_USER}" --password="${MYSQL_PASSWORD}" --database="${MYSQL_DATABASE}" --verbose
}

main "$@"
