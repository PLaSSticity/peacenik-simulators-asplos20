# A cmd used to recursively strip the "pseudo" prefix for all pseudo* stat files.
(remove -n to actually execute the cmd)

find . -iname "*pseudo*" -exec rename -n 's/pseudo//' '{}' \;