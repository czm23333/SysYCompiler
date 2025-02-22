#!/usr/bin/env bash
set -e

echo "--------Begin to Submit--------"

COURSE=PTC2022
MODULE=$(git rev-parse --abbrev-ref HEAD | tr '[a-z]' '[A-Z]')
WORKSPACE=$(basename $(realpath .))
FILE=submit.zip

if [[ $(git status --porcelain) ]]; then
  echo "Error: Git repository is dirty."
  echo "Commit all your changes before submitting."
  echo "Hint: run 'git status' to show changed files."
  exit -1
fi

# Construct assignmentId
ANTLRTMP="labN"

BRANCH=$(git symbolic-ref --short -q HEAD)
NUMBER=$(echo $BRANCH | tr -cd "[0-9]")
ID=$(echo $ANTLRTMP | sed "s/N/${NUMBER}/g")

echo "In branch: $BRANCH "
echo "Submit to assignment: $ID"


# Compress the whole folder instead of git storage only.
cd .. 
rm -f $FILE
zip -r "$FILE" $(ls -d "$WORKSPACE/.git" 2>/dev/null) > /dev/null
if [ $? -ne 0 ]; then
  echo ""
  echo "Fail to zip for submit.zip!"
else
  echo ""
  echo "generate submit.zip"
fi



