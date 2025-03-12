parser grammar SysYParser;

options {
    tokenVocab = SysYLexer;
}

program : (varDef | funcDef)*;

basicType : INT;
retType : basicType | VOID;

constPrefix : CONST?;
incompleteArray : L_BRACKT R_BRACKT;
arrayPostfixSingle : L_BRACKT length=expr R_BRACKT;
arrayPostfix : arrayPostfixSingle*;
eqInitializeVal : expr # single
                | L_BRACE (eqInitializeVal (COMMA eqInitializeVal)*)? R_BRACE # array;
initializer : ASSIGN value=eqInitializeVal # eqInitializer;
varDefEntry : name=IDENT arrayPostfix init=initializer?;
varDef : constPrefix type=basicType varDefEntry (COMMA varDefEntry)* SEMICOLON;

funcParam : type=basicType name=IDENT (incompleteArray arrayPostfix)?;
funcDef : r_type=retType name=IDENT L_PAREN (funcParam (COMMA funcParam)*)? R_PAREN stmtBlock;

leftVal : IDENT arrayPostfix;
funcRealParam : expr;
expr : value=INTEGER_CONST # const
     | func=IDENT L_PAREN (funcRealParam (COMMA funcRealParam)*)? R_PAREN # functionCall
     | leftVal # access
     | L_PAREN expr R_PAREN # paren
     | op=(PLUS | MINUS | NOT) x=expr # unary
     | l=expr op=(MUL | DIV | MOD) r=expr # muls
     | l=expr op=(PLUS | MINUS) r=expr # adds;

conds : expr # exprCond
     | l=expr op=(LT | GT | LE | GE) r=expr # rels
     | l=expr op=(EQ | NEQ) r=expr #eqs
     | l=conds AND r=conds # and
     | l=conds OR r=conds # or;

stmtBlock : L_BRACE (varDef | stmt)* R_BRACE;
stmt : expr? SEMICOLON # expression
     | lvalue=leftVal ASSIGN value=expr SEMICOLON # assignment
     | stmtBlock # block
     | IF L_PAREN cond=conds R_PAREN stmtTrue=stmt (ELSE stmtFalse=stmt)? # if
     | WHILE L_PAREN cond=conds R_PAREN stmtTrue=stmt # while
     | BREAK SEMICOLON # break
     | CONTINUE SEMICOLON # continue
     | RETURN ret=expr? SEMICOLON # return;