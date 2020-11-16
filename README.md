Это утилита, которая позволяет пересобрать git репозиторий коммит за коммитом, 
с возможностью изменить историю.

Возможные сферы применения:    
- исправление переносов \r на \n в истории  
- исправление символов табуляции в истории
- удаление лишних коммитов или даже целой цепочки коммитов
- глобальный squash коммитов по заданному правилу с целью очистки истории от бесполезны коммитов

Способ применения:  

```shell script
java -jar git-recreator.jar /path/to/original /path/to/recreate
```

Где:  
- `/path/to/original` - путь к репозиторию, который хотим менять
- `/path/to/recreate` - путь к папке, где будет создан измененный репозиторий

Оригинальный репозиторий не меняется.

Утилита в своей работе пользуется командами операционной системы: 
- `rm`
- `rsync`
- `git`

Эти команды должны присутсвовать через переменные среды.  
На Windows этого можно добиться, добавив Git Bash в переменный среды, 
а rsync можно установить так https://gist.github.com/hisplan/ee54e48f17b92c6609ac16f83073dde6.
На linux все проще, просто установите командой `apt install git rsync -y`.

Возможные флаги:  
-  `--continue` - в случае прерывания работы утилиты она сохраняет прогресс в файл 
git-recreator.json в рабочей папке. Если хочется запустить улититу заново, но продолжить с места, 
на котором закончили работу, достаточно добавить этот флаг в команду запуска утилиты в любое место.
-  `--delete-children hash1,hash2` - удалить коммит и все коммиты, для которых этот коммит является единственным предком.
Итоговая история не будет изменена, коммиты будут удалены, но их суммарные изменения попадут в merge-коммиты, которые связывали
удаленную цепочку с оставшимися цепочками.
